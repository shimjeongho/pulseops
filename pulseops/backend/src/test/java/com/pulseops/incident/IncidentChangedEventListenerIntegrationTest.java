package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.incident.dto.IncidentDetailResponse;
import com.pulseops.metric.MetricSnapshot;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 알림이 언제 밖으로 나가는지를 실제 Transaction 위에서 검증한다.
 * <p>
 * 이 계약은 흉내로는 확인할 수 없다. commit 이후에만 부른다는 것을 지키는 쪽이 Spring이고, "publish를
 * 몇 번 불렀는가"를 세는 것으로는 그 시점을 확인한 것이 되지 않는다. 그래서 실제 Transaction Manager를
 * 그대로 쓰고, commit 전과 후, 되돌아간 뒤를 각각 본다.
 * <p>
 * 전달만 대역으로 바꾼다. 실제 SSE 연결을 세우면 확인하려는 시점 대신 Socket 사정이 결과를 흔든다.
 * 그 자리에서 무엇을 하는지는 {@link IncidentSseBroadcasterTest}가 따로 본다.
 * <p>
 * Executor는 실제 Bean을 그대로 쓴다. 알림이 다른 Thread로 넘어가는지와 대기열이 가득 찼을 때 어떻게
 * 되는지가 여기서 볼 것이고, 그 둘은 실제 설정값 위에서만 뜻이 있다.
 * <p>
 * 기다릴 때 시간을 재지 않는다. 알림은 다른 Thread에서 일어나므로 얼마 뒤에 도착하는지 정해져 있지
 * 않고, 그것을 잠깐 자고 확인하는 방식으로 맞추면 느린 환경에서 이유 없이 실패한다. 대신 도착했다는
 * 사실 자체를 걸쇠로 기다린다. 반대로 "오지 않았다"는 것은 기다려서 확인할 수 없으므로, 뒤이어 실제로
 * 도착하는 알림 하나를 기준으로 삼는다. 전송 Thread는 하나이고 들어온 순서대로 처리하므로, 앞의 것이
 * 넘겨졌다면 이미 도착했어야 한다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentChangedEventListenerIntegrationTest {

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "POST";

	private static final String ENDPOINT = "/api/orders";

	private static final Instant OPENED_AT = Instant.parse("2026-09-01T06:00:00Z");

	private static final IncidentChangedEvent CHANGE = new IncidentChangedEvent(7L, OPENED_AT);

	private static final IncidentChangedEvent ROLLED_BACK_CHANGE = new IncidentChangedEvent(8L, OPENED_AT);

	/** 걸쇠가 열리지 않는 경우에도 테스트가 멈춰 있지 않도록 하는 상한이다. */
	private static final int WAIT_SECONDS = 30;

	/** 대기열을 채운 일들을 붙들어 두는 걸쇠이다. 어떤 결과로 끝나든 반드시 풀어 준다. */
	private final CountDownLatch release = new CountDownLatch(1);

	@MockitoBean
	private IncidentSseBroadcaster broadcaster;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	private ApplicationEventPublisher publisher;

	@Autowired
	private TaskExecutor incidentSseExecutor;

	@Autowired
	private IncidentService incidentService;

	@Autowired
	private IncidentRepository incidentRepository;

	/**
	 * Executor Bean은 Context를 함께 쓰는 다음 테스트도 쓴다. 붙들어 둔 일을 풀지 않고 끝나면 그 다음
	 * 테스트의 알림이 영원히 대기열에 남는다.
	 */
	@AfterEach
	void releaseAndClean() {
		this.release.countDown();
		this.incidentRepository.deleteAll();
	}

	/**
	 * 아직 되돌아갈 수 있는 변경을 알리면, 알림을 받고 다시 읽은 화면이 아무것도 달라지지 않은 상태를
	 * 보게 된다. 그때 화면은 자기가 빠른 것인지 서버가 틀린 것인지 구분할 방법이 없다.
	 */
	@Test
	void announcesTheChangeOnlyAfterTheTransactionCommits() throws InterruptedException {
		CountDownLatch announced = announcementLatch();

		this.transactionTemplate.executeWithoutResult((status) -> {
			this.publisher.publishEvent(CHANGE);
			then(this.broadcaster).should(never()).broadcast(any());
		});

		assertThat(announced.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
		then(this.broadcaster).should().broadcast(CHANGE);
	}

	/** 되돌아간 변경은 일어나지 않은 일이다. 그것을 알리면 화면이 없는 변화를 쫓는다. */
	@Test
	void announcesNothingWhenTheTransactionRollsBack() throws InterruptedException {
		CountDownLatch announced = announcementLatch();

		this.transactionTemplate.executeWithoutResult((status) -> {
			this.publisher.publishEvent(ROLLED_BACK_CHANGE);
			status.setRollbackOnly();
		});
		this.transactionTemplate.executeWithoutResult((status) -> this.publisher.publishEvent(CHANGE));

		assertThat(announced.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
		then(this.broadcaster).should().broadcast(CHANGE);
		then(this.broadcaster).should(never()).broadcast(ROLLED_BACK_CHANGE);
	}

	/**
	 * 알림을 commit한 Thread에서 그대로 보내면, 응답하지 않는 Client 하나가 확인 요청이나 Kafka 소비를
	 * 붙든다.
	 */
	@Test
	void announcesFromAThreadOfItsOwn() throws InterruptedException {
		AtomicReference<Thread> announcingThread = new AtomicReference<>();
		CountDownLatch announced = new CountDownLatch(1);
		willAnswer((invocation) -> {
			announcingThread.set(Thread.currentThread());
			announced.countDown();
			return null;
		}).given(this.broadcaster).broadcast(any());

		this.transactionTemplate.executeWithoutResult((status) -> this.publisher.publishEvent(CHANGE));

		assertThat(announced.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
		assertThat(announcingThread.get()).isNotSameAs(Thread.currentThread());
	}

	/**
	 * 알림을 넘길 자리가 없다고 해서 사람이 누른 확인이 실패하면 안 된다. 변경은 이미 저장되었고,
	 * 알림은 그것을 화면에 빨리 전하기 위한 수단일 뿐이다.
	 */
	@Test
	void keepsAcknowledgingWhileTheAnnouncementQueueIsFull() {
		fillTheAnnouncementQueue();
		Long incidentId = openIncident();

		IncidentDetailResponse detail = this.incidentService.acknowledge(incidentId);

		assertThat(detail.status()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		assertThat(this.incidentRepository.findById(incidentId)).get()
			.extracting(Incident::getStatus)
			.isEqualTo(IncidentStatus.ACKNOWLEDGED);
	}

	/**
	 * Thread 하나를 붙들고 대기열을 끝까지 채운다. 넣는 수를 여기서 다시 정하지 않고 Executor 설정에서
	 * 가져오는 것은, 설정이 바뀌었을 때 이 테스트가 더 이상 가득 찬 상태를 만들지 못하는 것을 조용히
	 * 놓치지 않기 위해서이다.
	 */
	private void fillTheAnnouncementQueue() {
		int occupied = IncidentSseExecutorConfiguration.POOL_SIZE + IncidentSseExecutorConfiguration.QUEUE_CAPACITY;
		for (int slot = 0; slot < occupied; slot++) {
			this.incidentSseExecutor.execute(this::awaitRelease);
		}
	}

	private void awaitRelease() {
		try {
			this.release.await(WAIT_SECONDS, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private CountDownLatch announcementLatch() {
		CountDownLatch announced = new CountDownLatch(1);
		willAnswer((invocation) -> {
			announced.countDown();
			return null;
		}).given(this.broadcaster).broadcast(any());
		return announced;
	}

	private Long openIncident() {
		MetricSnapshot trigger = new MetricSnapshot(SERVICE, METHOD, ENDPOINT, OPENED_AT.minusSeconds(60), OPENED_AT,
				120, 30, 25.0, 240L);
		Incident incident = Incident.open(IncidentType.ERROR_RATE, IncidentSeverity.WARNING, trigger, OPENED_AT);
		return this.incidentRepository.save(incident).getId();
	}

}

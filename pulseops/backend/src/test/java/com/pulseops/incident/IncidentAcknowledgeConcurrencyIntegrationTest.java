package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.metric.MetricSeries;
import com.pulseops.metric.MetricSnapshot;
import com.pulseops.metric.MetricWindow;
import com.pulseops.metric.RollingMetricService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 사람의 확인 처리와 실시간 판정이 같은 Incident를 동시에 바꿀 때 무엇이 남는지 검증한다.
 * <p>
 * 이 자리가 이번 단계의 핵심이다. 확인 처리는 사람이 아무 때나 누르는 것이고 판정은 Event가 오는
 * 대로 도는 것이라, 두 흐름이 같은 Incident를 동시에 건드리는 것은 예외적인 상황이 아니라 일상이다.
 * 그때 지켜져야 하는 것은 둘 다 성공하는 것이 아니라, 어느 쪽도 상대의 변경을 지우지 않는 것이다.
 * 회복이 사라지면 끝난 장애가 계속 열려 있고, 악화가 사라지면 더 나빠진 장애가 가벼워 보인다.
 * <p>
 * 두 흐름은 Incident를 같은 자물쇠로 잠근다. 판정은 계열로 ID를 알아낸 뒤, 확인 처리는 받은 ID로,
 * 둘 다 PK를 정확히 하나 짚는 조회로 잠근다. PK 조회는 gap을 잡지 않으므로 뒤에 온 쪽은 물러나지
 * 않고 앞선 쪽의 commit을 기다렸다가 그 위에서 이어 간다.
 * <p>
 * 확인 처리는 DetectionState를 아예 요청하지 않으므로 그 둘 사이에도 서로가 쥔 것을 기다리는 고리가
 * 없다. 교착으로 물러나는 결과는 정상이 아니며, 그것이 관측되면 이 Class는 즉시 실패한다.
 * <p>
 * 어느 쪽이 먼저 잠그는지 강제하지 않는다. 순서를 고정하려면 production 코드에 시험용 걸쇠를 넣어야
 * 하는데, 그렇게 만든 순서는 실제로 일어나는 순서가 아니라 시험이 만든 순서다. 대신 두 흐름을 같은
 * 지점에서 출발시키고, 어느 순서로 가더라도 반드시 참이어야 하는 것만 단언한다. 순서에 따라 달라지는
 * 값은 두 가능한 결과를 모두 허용하되 그 둘이 서로 모순되지 않는지까지 확인한다.
 * <p>
 * 어느 쪽도 다시 시도하지 않는다. 한 번의 동시 실행에서 최종 상태가 나와야 하며, 다시 해서 맞추는
 * 것은 직렬화가 되었다는 증거가 아니다.
 * <p>
 * 판정은 실제 {@link IncidentDetector}를 그대로 쓴다. 흉내 내는 것은 Redis에서 창을 읽는 부분뿐이며,
 * 잠금과 상태 전이는 production과 같은 경로를 지난다. 검증하려는 것이 바로 그 경로이기 때문이다.
 * <p>
 * 창은 오류율만 이상으로 판정되도록 만든다. 응답 시간까지 함께 이상이면 Incident가 종류마다 하나씩
 * 열려, 확인 처리한 것과 판정이 바꾼 것이 같은 Incident인지가 흐려진다.
 * <p>
 * 시각으로 순서를 맞추지 않는다. 두 흐름은 {@link CyclicBarrier}에서 함께 출발하며 그 뒤의 순서는
 * DB의 잠금이 정한다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentAcknowledgeConcurrencyIntegrationTest {

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "GET";

	private static final String ENDPOINT = "/api/orders/{orderId}";

	private static final MetricSeries SERIES = new MetricSeries(SERVICE, METHOD, ENDPOINT);

	private static final Instant WINDOW_1 = Instant.parse("2026-09-01T12:00:30Z");

	private static final int SECONDS_INTO_THE_FILLING_BUCKET = 7;

	private static final int CONCURRENT_ACTORS = 2;

	/** 잠금을 기다리다 끝나지 않는 경우에도 테스트가 멈춰 있지 않도록 하는 상한이다. */
	private static final int WAIT_SECONDS = 60;

	/** 경쟁의 결과가 실행마다 달라질 수 있으므로 한 번의 성공을 결론으로 삼지 않는다. */
	private static final int RACE_REPETITIONS = 5;

	private final RollingMetricService rollingMetricService = mock(RollingMetricService.class);

	@Autowired
	private DetectionStateRepository detectionStateRepository;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private IncidentService incidentService;

	@Autowired
	private TransactionTemplate transactionTemplate;

	/**
	 * 실제 Publisher를 그대로 쓴다. Incident 변경 알림이 붙은 뒤에도 두 흐름이 서로를 기다리며 멈추지
	 * 않는지가 이 Class가 지키는 것에 들어가므로, 알림 경로만 대역으로 바꾸면 확인하려는 경쟁이 실제와
	 * 달라진다.
	 */
	@Autowired
	private ApplicationEventPublisher eventPublisher;

	private IncidentDetector detector;

	@BeforeEach
	void createDetector() {
		this.detector = new IncidentDetector(this.rollingMetricService, this.detectionStateRepository,
				this.incidentRepository, this.transactionTemplate, this.eventPublisher);
	}

	@AfterEach
	void removeDetectionResults() {
		this.incidentRepository.deleteAll();
		this.detectionStateRepository.deleteAll();
	}

	/**
	 * 두 사람이 같은 Incident의 확인 버튼을 동시에 누르는 경우이다. 둘 다 성공하면 나중 요청이 앞
	 * 요청의 확인 시각을 덮어써, 누가 언제부터 보고 있었는지가 사라진다.
	 */
	@RepeatedTest(RACE_REPETITIONS)
	void letsExactlyOneOfTwoSimultaneousAcknowledgementsWin() {
		Long incidentId = openIncident();

		List<Outcome> outcomes = runTogether(acknowledging(incidentId), acknowledging(incidentId));

		assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
		assertThat(outcomes).filteredOn((outcome) -> outcome.failedWith(IncidentAcknowledgeNotAllowedException.class))
			.hasSize(1);
		assertThat(reload(incidentId)).satisfies((incident) -> {
			assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
			assertThat(incident.getAcknowledgedAt()).isNotNull();
		});
	}

	/**
	 * 사람이 확인하는 사이에 서비스가 회복되어 판정이 Incident를 끝내는 경우이다.
	 * <p>
	 * 어느 쪽이 먼저 잠그느냐에 따라 확인은 성공할 수도 거절될 수도 있다. 먼저 확인되면 판정은 확인된
	 * Incident를 끝내고, 먼저 끝나면 확인은 끝난 Incident를 확인할 수 없다고 거절된다. 두 결과 모두
	 * 정상이며, 그 밖의 결과는 없어야 한다.
	 * <p>
	 * 특히 한 번의 동시 실행으로 끝나야 한다. 다시 판정해서 RESOLVED에 이르는 것은 여기서 확인하려는
	 * 것이 아니다. 두 흐름이 같은 Index 순서로 잠그면 뒤에 온 쪽이 앞선 쪽의 commit을 기다렸다가
	 * 이어서 진행하므로, 물러나거나 다시 할 일이 생기지 않는다.
	 */
	@RepeatedTest(RACE_REPETITIONS)
	void resolvesTheIncidentEvenWhenAnAcknowledgementRacesTheDetection() {
		Long incidentId = openIncident();
		detectOnce(healthy(window(3)));
		detectOnce(healthy(window(4)));
		MetricSnapshot recovering = healthy(window(5));

		List<Outcome> outcomes = runTogether(acknowledging(incidentId), detecting(recovering));

		assertThat(wasEvaluated(recovering.windowEnd()))
			.withFailMessage("판정이 한 번에 끝나지 않고 되돌아갔다")
			.isTrue();
		Incident incident = reload(incidentId);
		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
		assertThat(incident.getResolvedAt()).isNotNull();
		// 확인이 먼저였다면 그 사실이 남고, 판정이 먼저였다면 확인은 거절된다. 그 둘뿐이다.
		if (outcomes.get(0).succeeded()) {
			assertThat(incident.getAcknowledgedAt()).isNotNull();
		}
		else {
			assertThat(outcomes.get(0).failedWith(IncidentAcknowledgeNotAllowedException.class)).isTrue();
			assertThat(incident.getAcknowledgedAt()).isNull();
		}
	}

	/**
	 * 사람이 확인하는 사이에 상태가 더 나빠져 판정이 심각도를 올리는 경우이다.
	 * <p>
	 * 두 변경이 서로 다른 값을 건드리므로 한 번의 동시 실행에서 둘 다 남아야 한다. 확인은 상태를,
	 * 악화는 심각도를 바꾸며 어느 쪽도 상대의 자리를 쓰지 않는다. 하나라도 사라진다면 두 Transaction이
	 * 서로의 변경을 보지 못한 채 자기가 읽은 Incident를 통째로 써 넣었다는 뜻이다.
	 */
	@RepeatedTest(RACE_REPETITIONS)
	void keepsBothTheAcknowledgementAndTheEscalation() {
		Long incidentId = openWarningIncident();
		MetricSnapshot worse = criticalErrorRate(window(3));

		List<Outcome> outcomes = runTogether(acknowledging(incidentId), detecting(worse));

		assertThat(outcomes).allSatisfy(Outcome::assertSucceeded);
		assertThat(wasEvaluated(worse.windowEnd())).withFailMessage("판정이 한 번에 끝나지 않고 되돌아갔다").isTrue();
		assertThat(reload(incidentId)).satisfies((incident) -> {
			assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
			assertThat(incident.getAcknowledgedAt()).isNotNull();
			assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
		});
	}

	/**
	 * 확인 처리가 계열로 잠그기 때문에 생기는 위험을 고정한다.
	 * <p>
	 * 끝난 Incident의 자리에는 같은 계열의 새 Incident가 열릴 수 있다. 확인 처리는 ID로 계열을 알아낸
	 * 뒤 그 계열의 활성 Incident를 잠그므로, 그 사이에 자리가 넘어가면 요청받지 않은 Incident가 잠긴
	 * 채로 손에 들어온다. 그것을 확인 처리하면 사람이 본 적 없는 장애가 확인된 것으로 표시된다.
	 * <p>
	 * 잠근 뒤 ID를 확인하므로 요청받은 것도 새로 열린 것도 바뀌지 않고, 요청은 거절된다.
	 */
	@Test
	void neverAcknowledgesTheIncidentThatTookOverTheSeries() {
		Long resolvedId = openIncident();
		detectOnce(healthy(window(3)));
		detectOnce(healthy(window(4)));
		detectOnce(healthy(window(5)));
		detectOnce(criticalErrorRate(window(6)));
		detectOnce(criticalErrorRate(window(7)));
		Long recurrenceId = activeIncidentId();

		assertThat(recurrenceId).isNotEqualTo(resolvedId);
		assertThatExceptionOfType(IncidentAcknowledgeNotAllowedException.class)
			.isThrownBy(() -> this.incidentService.acknowledge(resolvedId));

		assertThat(reload(resolvedId).getAcknowledgedAt()).isNull();
		assertThat(reload(recurrenceId)).satisfies((recurrence) -> {
			assertThat(recurrence.getStatus()).isEqualTo(IncidentStatus.OPEN);
			assertThat(recurrence.getAcknowledgedAt()).isNull();
		});
	}

	/** 판정이 어디까지 보았는지이다. 이 창에 닿아 있으면 그 판정 Transaction이 commit된 것이다. */
	private boolean wasEvaluated(Instant windowEnd) {
		return this.detectionStateRepository.findAll()
			.stream()
			.filter((state) -> state.getType() == IncidentType.ERROR_RATE)
			.anyMatch((state) -> !state.getLastEvaluatedWindowEnd().isBefore(windowEnd));
	}

	/**
	 * 확인 처리는 판정이 세는 연속 횟수나 어디까지 보았는지와 아무 관계가 없다. 확인이 그 값을
	 * 건드리면 사람이 버튼을 누른 것 때문에 다음 판정이 창을 다시 세거나 건너뛴다.
	 */
	@Test
	void leavesTheDetectionStateAloneWhenAcknowledging() {
		Long incidentId = openIncident();
		List<DetectionStateSnapshot> before = detectionStates();

		this.incidentService.acknowledge(incidentId);

		assertThat(detectionStates()).isEqualTo(before);
	}

	/**
	 * 확인된 Incident도 여전히 처리 중이므로 판정이 계속 찾아낼 수 있어야 한다. 확인 때문에 판정에서
	 * 사라지면 사람이 버튼을 누르는 순간부터 그 장애는 영영 끝나지 않는다.
	 */
	@Test
	void keepsAnAcknowledgedIncidentVisibleToDetection() {
		Long incidentId = openIncident();
		this.incidentService.acknowledge(incidentId);

		detectOnce(healthy(window(3)));
		detectOnce(healthy(window(4)));
		detectOnce(healthy(window(5)));

		assertThat(reload(incidentId).getStatus()).isEqualTo(IncidentStatus.RESOLVED);
	}

	/** 이상이 두 창 연속되면 Incident가 열린다. 창 1과 2를 쓰므로 이어지는 판정은 창 3부터이다. */
	private Long openIncident() {
		detectOnce(criticalErrorRate(window(1)));
		detectOnce(criticalErrorRate(window(2)));
		return onlyIncident().getId();
	}

	private Long openWarningIncident() {
		detectOnce(warningErrorRate(window(1)));
		detectOnce(warningErrorRate(window(2)));
		Incident incident = onlyIncident();
		assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.WARNING);
		return incident.getId();
	}

	private Incident onlyIncident() {
		List<Incident> incidents = this.incidentRepository.findAll();
		assertThat(incidents).hasSize(1);
		return incidents.get(0);
	}

	private Long activeIncidentId() {
		List<Incident> active = this.incidentRepository.findActive();
		assertThat(active).hasSize(1);
		return active.get(0).getId();
	}

	private Incident reload(Long incidentId) {
		return this.incidentRepository.findById(incidentId).orElseThrow();
	}

	private List<DetectionStateSnapshot> detectionStates() {
		return this.detectionStateRepository.findAll()
			.stream()
			.map((state) -> new DetectionStateSnapshot(state.getType(), state.getLastEvaluatedWindowEnd(),
					state.getAbnormalConsecutive(), state.getRecoveryConsecutive()))
			.sorted((left, right) -> left.type().compareTo(right.type()))
			.toList();
	}

	private void detectOnce(MetricSnapshot snapshot) {
		given(this.rollingMetricService.snapshot(eq(SERIES), any())).willReturn(snapshot);
		this.detector.detect(SERIES, evaluationNowFor(snapshot));
	}

	private Callable<Void> acknowledging(Long incidentId) {
		return () -> {
			this.incidentService.acknowledge(incidentId);
			return null;
		};
	}

	/**
	 * 창은 미리 정해 두고 넘긴다. Detector가 읽는 창을 흉내 낸 Service가 돌려주는데, 그 흉내를 두
	 * 흐름이 동시에 고쳐 쓰면 무엇을 판정하는지가 실행마다 달라진다.
	 */
	private Callable<Void> detecting(MetricSnapshot snapshot) {
		given(this.rollingMetricService.snapshot(eq(SERIES), any())).willReturn(snapshot);
		Instant evaluationNow = evaluationNowFor(snapshot);
		return () -> {
			this.detector.detect(SERIES, evaluationNow);
			return null;
		};
	}

	/**
	 * 두 흐름을 같은 지점에서 출발시키고 둘 다 끝날 때까지 기다린 뒤, 각자가 성공했는지 어떤 실패로
	 * 끝났는지를 그대로 돌려준다. 실패를 여기서 단언하지 않는 것은 어느 쪽이 실패하는지가 잠금 순서에
	 * 따라 달라지기 때문이며, 무엇이 허용되는 결과인지는 부르는 테스트가 정한다.
	 */
	private List<Outcome> runTogether(Callable<Void> first, Callable<Void> second) {
		CyclicBarrier startTogether = new CyclicBarrier(CONCURRENT_ACTORS);
		ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_ACTORS);
		try {
			List<Callable<Void>> actors = new ArrayList<>();
			for (Callable<Void> actor : List.of(first, second)) {
				actors.add(() -> {
					startTogether.await(WAIT_SECONDS, TimeUnit.SECONDS);
					return actor.call();
				});
			}
			List<Outcome> outcomes = collect(executor.invokeAll(actors, WAIT_SECONDS, TimeUnit.SECONDS));
			outcomes.forEach(Outcome::assertNotBlockedByALock);
			return outcomes;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("동시 실행을 기다리는 중에 중단되었다", ex);
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static List<Outcome> collect(List<Future<Void>> futures) throws InterruptedException {
		List<Outcome> outcomes = new ArrayList<>();
		for (Future<Void> future : futures) {
			try {
				future.get();
				outcomes.add(new Outcome(null));
			}
			catch (ExecutionException ex) {
				outcomes.add(new Outcome(ex.getCause()));
			}
		}
		return outcomes;
	}

	/** 판정할 창이 막 닫히고 다음 Bucket이 채워지는 중인 시각이다. */
	private static Instant evaluationNowFor(MetricSnapshot snapshot) {
		return snapshot.windowEnd().plusSeconds(SECONDS_INTO_THE_FILLING_BUCKET);
	}

	private static Instant window(int number) {
		return WINDOW_1.plusSeconds((long) MetricWindow.BUCKET_SECONDS * (number - 1));
	}

	/** 오류율만 CRITICAL이고 응답 시간은 정상인 창이다. Incident가 오류율 하나만 열린다. */
	private static MetricSnapshot criticalErrorRate(Instant windowEnd) {
		return snapshot(windowEnd, 30, 30.0);
	}

	private static MetricSnapshot warningErrorRate(Instant windowEnd) {
		return snapshot(windowEnd, 15, 15.0);
	}

	/** 두 종류 모두 회복으로 판정되는 창이다. */
	private static MetricSnapshot healthy(Instant windowEnd) {
		return snapshot(windowEnd, 0, 0.0);
	}

	private static MetricSnapshot snapshot(Instant windowEnd, long errorCount, double errorRate) {
		Instant windowStart = windowEnd
			.minusSeconds((long) MetricWindow.BUCKET_SECONDS * MetricWindow.WINDOW_BUCKETS);
		return new MetricSnapshot(SERVICE, METHOD, ENDPOINT, windowStart, windowEnd, 100, errorCount, errorRate, 100L);
	}

	/** 한 흐름이 어떻게 끝났는지이다. 성공이면 원인이 없고 실패면 밖으로 나온 예외를 그대로 담는다. */
	private record Outcome(Throwable failure) {

		private boolean succeeded() {
			return this.failure == null;
		}

		private boolean failedWith(Class<? extends Throwable> type) {
			return type.isInstance(this.failure);
		}

		private void assertSucceeded() {
			if (this.failure != null) {
				fail("동시 실행이 실패했다", this.failure);
			}
		}

		/**
		 * 잠금 때문에 물러난 것은 정상 결과가 아니다. 두 흐름이 같은 자물쇠를 같은 방식으로 잡으므로
		 * 뒤에 온 쪽은 기다렸다가 이어서 진행해야 하며, 교착으로 물리는 것은 그 전제가 깨졌다는 뜻이다.
		 */
		private void assertNotBlockedByALock() {
			if (this.failure instanceof PessimisticLockingFailureException) {
				fail("잠금 경쟁으로 Transaction이 물러났다", this.failure);
			}
		}

	}

	/** 확인 처리 전후를 비교하기 위해 판정 상태에서 값만 떼어 낸 것이다. */
	private record DetectionStateSnapshot(IncidentType type, Instant lastEvaluatedWindowEnd, int abnormalConsecutive,
			int recoveryConsecutive) {

	}

}

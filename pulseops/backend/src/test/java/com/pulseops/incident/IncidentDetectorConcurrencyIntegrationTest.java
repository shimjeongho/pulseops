package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 같은 계열의 같은 창을 두 흐름이 동시에 판정할 때 무엇이 남는지 검증한다.
 * <p>
 * Kafka Consumer가 여럿이 되거나 Application이 여러 Instance로 뜨면 실제로 일어나는 상황이다.
 * 그때 지켜져야 하는 것은 두 흐름이 모두 성공하는 것이 아니라, 결과가 한 흐름만 돌았을 때와 같아야
 * 한다는 것이다. 창 하나는 한 번만 세어지고 처리 중인 Incident는 하나여야 한다.
 * <p>
 * 두 흐름이 반드시 부딪히도록 강제하지 않는다. 잠금이 언제 걸리느냐에 따라 한쪽이 상대의 commit을
 * 기다렸다가 이미 본 창임을 알고 물러날 수도 있고, 상태를 처음 만드는 순간이면 identity 제약에
 * 걸려 이번 판정을 통째로 잃을 수도 있다. 검증하는 것은 그 중 어느 길로 갔는지가 아니라 어느 길로
 * 가더라도 남는 결과가 같다는 사실이다. 특정 순서를 기다리게 만들면 그 순서를 강제하는 장치가
 * 검증 대상보다 복잡해지고, 실행 환경이 조금만 달라져도 흔들린다.
 * <p>
 * 시각으로 순서를 맞추지 않는다. 두 흐름은 {@link CyclicBarrier}에서 함께 출발하며, 그 뒤의 순서는
 * DB의 잠금이 정한다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentDetectorConcurrencyIntegrationTest {

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "GET";

	private static final String ENDPOINT = "/api/orders/{orderId}";

	private static final MetricSeries SERIES = new MetricSeries(SERVICE, METHOD, ENDPOINT);

	private static final Instant WINDOW_1 = Instant.parse("2026-08-31T12:00:30Z");

	private static final Instant WINDOW_2 = WINDOW_1.plusSeconds(MetricWindow.BUCKET_SECONDS);

	private static final int SECONDS_INTO_THE_FILLING_BUCKET = 7;

	private static final int CONCURRENT_DETECTIONS = 2;

	/** 잠금을 기다리다 끝나지 않는 경우에도 테스트가 멈춰 있지 않도록 하는 상한이다. */
	private static final int WAIT_SECONDS = 60;

	private final RollingMetricService rollingMetricService = mock(RollingMetricService.class);

	@Autowired
	private DetectionStateRepository detectionStateRepository;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private IncidentDetector detector;

	@BeforeEach
	void createDetector() {
		this.detector = new IncidentDetector(this.rollingMetricService, this.detectionStateRepository,
				this.incidentRepository, this.transactionTemplate);
	}

	@AfterEach
	void removeDetectionResults() {
		this.incidentRepository.deleteAll();
		this.detectionStateRepository.deleteAll();
	}

	/**
	 * 상태가 아직 없을 때가 가장 위험한 자리다. 잠글 행이 없어 두 흐름이 나란히 "없다"를 볼 수 있고,
	 * 그때 상태가 둘로 갈라지면 연속 횟수가 나뉘어 어느 쪽도 기준에 닿지 못한다.
	 */
	@Test
	void createsAtMostOneStatePerTypeWhenTheSeriesIsSeenForTheFirstTime() {
		detectConcurrently(abnormal(WINDOW_1));

		assertThat(this.detectionStateRepository.findAll()).hasSize(IncidentType.values().length)
			.allSatisfy((state) -> {
				assertThat(state.getLastEvaluatedWindowEnd()).isEqualTo(WINDOW_1);
				assertThat(state.getAbnormalConsecutive()).isEqualTo(1);
			})
			.extracting(DetectionState::getType)
			.containsExactlyInAnyOrder(IncidentType.values());
		assertThat(this.incidentRepository.count()).isZero();
	}

	/**
	 * 연속을 완성하는 창이 동시에 두 번 판정되어도 기록은 하나여야 한다. 하나가 상태를 고쳐 쓰는
	 * 동안 다른 하나가 같은 값을 읽으면, 같은 장애에 대한 기록이 둘로 갈라진다.
	 */
	@Test
	void opensExactlyOneIncidentWhenTheTriggeringWindowIsDetectedTwiceAtOnce() {
		detectOnce(abnormal(WINDOW_1));

		detectConcurrently(abnormal(WINDOW_2));

		assertThat(this.incidentRepository.findAll()).hasSize(IncidentType.values().length)
			.allSatisfy((incident) -> assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN))
			.extracting(Incident::getType)
			.containsExactlyInAnyOrder(IncidentType.values());
		assertThat(this.detectionStateRepository.findAll()).hasSize(IncidentType.values().length)
			.allSatisfy((state) -> {
				assertThat(state.getLastEvaluatedWindowEnd()).isEqualTo(WINDOW_2);
				assertThat(state.getAbnormalConsecutive()).isEqualTo(2);
			});
	}

	private void detectOnce(MetricSnapshot snapshot) {
		given(this.rollingMetricService.snapshot(eq(SERIES), any())).willReturn(snapshot);
		this.detector.detect(SERIES, evaluationNowFor(snapshot));
	}

	/**
	 * 두 흐름을 같은 지점에서 출발시키고 둘 다 끝날 때까지 기다린다. Detection이 예외를 밖으로
	 * 내보내지 않는 것도 함께 확인되는데, {@link Future#get}이 그 예외를 그대로 다시 던지기 때문이다.
	 */
	private void detectConcurrently(MetricSnapshot snapshot) {
		given(this.rollingMetricService.snapshot(eq(SERIES), any())).willReturn(snapshot);
		Instant evaluationNow = evaluationNowFor(snapshot);
		CyclicBarrier startTogether = new CyclicBarrier(CONCURRENT_DETECTIONS);
		ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_DETECTIONS);
		try {
			List<Callable<Void>> detections = new ArrayList<>();
			for (int detection = 0; detection < CONCURRENT_DETECTIONS; detection++) {
				detections.add(() -> {
					startTogether.await(WAIT_SECONDS, TimeUnit.SECONDS);
					this.detector.detect(SERIES, evaluationNow);
					return null;
				});
			}
			awaitAll(executor.invokeAll(detections, WAIT_SECONDS, TimeUnit.SECONDS));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("동시 판정을 기다리는 중에 중단되었다", ex);
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static void awaitAll(List<Future<Void>> detections) throws InterruptedException {
		for (Future<Void> detection : detections) {
			try {
				detection.get();
			}
			catch (ExecutionException ex) {
				throw new IllegalStateException("동시 판정 중 예외가 밖으로 나왔다", ex.getCause());
			}
		}
	}

	/** 판정할 창이 막 닫히고 다음 Bucket이 채워지는 중인 시각이다. */
	private static Instant evaluationNowFor(MetricSnapshot snapshot) {
		return snapshot.windowEnd().plusSeconds(SECONDS_INTO_THE_FILLING_BUCKET);
	}

	/** 두 종류 모두 이상으로 판정되는 창이다. 한 판정으로 두 종류의 잠금 순서를 함께 지나간다. */
	private static MetricSnapshot abnormal(Instant windowEnd) {
		Instant windowStart = windowEnd
			.minusSeconds((long) MetricWindow.BUCKET_SECONDS * MetricWindow.WINDOW_BUCKETS);
		return new MetricSnapshot(SERVICE, METHOD, ENDPOINT, windowStart, windowEnd, 100, 30, 30.0, 4000L);
	}

}

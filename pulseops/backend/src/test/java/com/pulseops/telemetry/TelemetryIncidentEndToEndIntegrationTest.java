package com.pulseops.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.incident.DetectionState;
import com.pulseops.incident.DetectionStateRepository;
import com.pulseops.incident.Incident;
import com.pulseops.incident.IncidentDetector;
import com.pulseops.incident.IncidentRepository;
import com.pulseops.incident.IncidentSeverity;
import com.pulseops.incident.IncidentStatus;
import com.pulseops.incident.IncidentType;
import com.pulseops.metric.MetricRecordOutcome;
import com.pulseops.metric.MetricRecorder;
import com.pulseops.metric.MetricSeries;
import com.pulseops.metric.MetricSnapshot;
import com.pulseops.metric.MetricWindow;
import com.pulseops.metric.RollingMetricService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Telemetry Event가 실제 Kafka, Redis, MySQL을 지나 Incident로 남기까지의 경로 전체를 검증한다.
 * <p>
 * 계층마다 이미 자기 테스트가 있고 판정 규칙도 Snapshot 대역으로 검증되어 있다. 여기서 확인하는 것은
 * 그 계층들이 실제 저장소 위에서 정말로 이어져 있는가, 그리고 실제 Redis Bucket에서 계산된 창이 실제
 * MySQL에 Incident를 여는가이다. 그래서 어떤 협력자도 대역으로 바꾸지 않는다.
 * <p>
 * 검증을 둘로 나눈 이유는 판정 기준 시각 때문이다. Kafka 경로의 기준 시각은
 * {@link TelemetryProcessingService}가 읽는 벽시계이고, 그 시각이 속한 Bucket이 곧 평가할 창의
 * 끝이다. 그래서 서로 다른 두 창을 Kafka Event만으로 만들려면 실제로 Bucket 경계를 넘길 만큼
 * 기다려야 한다. 10초를 기다리는 테스트는 느린 것보다 나쁘게, 경계 근처에서 결과가 갈리는 테스트가
 * 된다. 판정을 결정론적으로 만들려고 Production에 Clock을 주입하지도 않는다. 그것은 검증 대상인
 * 구조를 검증하기 편한 구조로 바꾸는 일이다.
 * <p>
 * 대신 배선과 판정을 각각 실제 인프라 위에서 확인한다. 첫 검증은 Kafka Record들이 Redis Metric과
 * MySQL Detection State까지 실제로 닿는지를 보고, 나머지는 같은 Redis 쓰기 경로로 창을 만든 뒤 판정
 * 기준 시각만 테스트가 정해 연속된 두 창이 Incident를 여는지를 본다. 두 검증이 공유하는 것은 실제
 * Redis Bucket과 실제 MySQL 행이며 그 사이에 대역이 끼어들지 않는다.
 * <p>
 * 모든 창 경계는 test method가 한 번 읽은 현재 시각에서 계산한다. 여러 번 읽으면 그 사이에 Bucket
 * 경계가 지날 때 기대값과 실제 창이 어긋난다.
 * <p>
 * 각 검증은 자기만의 계열과 eventId를 쓰고 자기가 만든 Key만 지운다. 그래서 하나의 Topic과 하나의
 * Redis를 공유해도 실행 순서에 의존하지 않는다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelemetryIncidentEndToEndIntegrationTest {

	private static final String KAFKA_IMAGE = "apache/kafka:4.0.0";

	private static final String REDIS_IMAGE = "redis:8.4.2-alpine";

	private static final int REDIS_PORT = 6379;

	/** Phase 2에서 확정한 Topic 구성이다. Broker의 auto creation에 맡기면 partition이 하나가 된다. */
	private static final int EXPECTED_PARTITIONS = 3;

	private static final int EXPECTED_REPLICAS = 1;

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "GET";

	private static final MetricSeries HOT_PATH_SERIES = new MetricSeries(SERVICE, METHOD,
			"/api/e2e/hot-path/{probeId}");

	private static final MetricSeries INCIDENT_SERIES = new MetricSeries(SERVICE, METHOD,
			"/api/e2e/incident/{probeId}");

	private static final MetricSeries CONTINUED_SERIES = new MetricSeries(SERVICE, METHOD,
			"/api/e2e/continued/{probeId}");

	private static final int WINDOW_SECONDS = MetricWindow.BUCKET_SECONDS * MetricWindow.WINDOW_BUCKETS;

	/**
	 * Bucket 하나에 넣는 요청 수이다. 창은 6 Bucket이므로 어느 창에서 보아도 요청이 24건이 되어 판정에
	 * 필요한 최소 요청 수 20을 넘는다.
	 */
	private static final int REQUESTS_PER_BUCKET = 4;

	private static final long EXPECTED_WINDOW_REQUEST_COUNT = (long) REQUESTS_PER_BUCKET
			* MetricWindow.WINDOW_BUCKETS;

	private static final int ERROR_STATUS = 500;

	private static final int SUCCESS_STATUS = 200;

	/** 이 아래의 응답은 오류로 세지 않는다. Event의 success가 갈리는 경계이기도 하다. */
	private static final int FIRST_FAILURE_STATUS = 400;

	/**
	 * 모든 Event가 이 응답 시간을 쓴다. 회복 기준선인 500ms보다 낮아 어느 창에서도 P95가 정상으로
	 * 판정되므로, 오류율로 열린 Incident 옆에서 응답 시간 Incident가 함께 열리지 않는다. 두 종류가 같은
	 * 트래픽에서 서로 독립적으로 판정된다는 것을 이 값 하나로 고정한다.
	 */
	private static final long HEALTHY_LATENCY_MS = 100;

	private static final double CRITICAL_ERROR_RATE_PERCENT = 100.0;

	/** 창이 닫힌 뒤 다음 Bucket이 채워지는 중인 시각이다. 판정은 그 Bucket을 보면 안 된다. */
	private static final int SECONDS_INTO_THE_FILLING_BUCKET = 7;

	/** 발생 시각을 Bucket 경계에 딱 붙이지 않는다. 경계 값은 어느 Bucket의 것인지 읽기 어렵다. */
	private static final int SECONDS_INTO_THE_BUCKET = 1;

	private static final String FIXTURE_PATH = "telemetry/telemetry-event-v1.json";

	private static final Duration METRIC_TIMEOUT = Duration.ofSeconds(60);

	private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

	private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private KafkaContainer kafkaContainer;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private MetricRecorder metricRecorder;

	@Autowired
	private RollingMetricService rollingMetricService;

	@Autowired
	private IncidentDetector incidentDetector;

	@Autowired
	private DetectionStateRepository detectionStateRepository;

	@Autowired
	private IncidentRepository incidentRepository;

	private KafkaProducer<String, String> producer;

	/** 자기가 만든 Key만 지운다. 전체를 비우면 같은 Redis를 쓰는 다른 상태까지 함께 사라진다. */
	private final Set<String> writtenKeys = new HashSet<>();

	@BeforeAll
	void createProducer() {
		this.producer = new KafkaProducer<>(producerConfig());
	}

	@AfterAll
	void closeProducer() {
		this.producer.close();
	}

	@AfterEach
	void removeWrittenKeysAndDetectionResults() {
		this.redisTemplate.delete(this.writtenKeys);
		this.writtenKeys.clear();
		this.incidentRepository.deleteAll();
		this.detectionStateRepository.deleteAll();
	}

	/**
	 * Kafka Record들이 실제로 하나의 창을 이루고 그 창이 판정까지 닿는지를 본다.
	 * <p>
	 * Event 하나가 Redis에 남는지는 Phase 3에서, 그 사실이 판정을 부르는지는 Phase 4-6에서 이미
	 * 확인했다. 여기서 더 보는 것은 Kafka로 들어온 Event들이 판정할 만큼의 표본을 가진 창을 실제로
	 * 만드는가이다. 요청 수가 판정 기준에 닿지 못하면 그 뒤의 모든 판정은 트래픽 부족으로 끝난다.
	 * <p>
	 * 트래픽은 전부 정상 응답이다. Kafka 경로의 판정 기준 시각은 벽시계라 소비 도중 Bucket 경계가
	 * 지나면 창이 하나 더 평가될 수 있다. 이상 트래픽을 보내면 그 우연한 두 번째 창 때문에 Incident가
	 * 열렸다 열리지 않았다 한다. 정상 창은 몇 번을 평가해도 Incident를 만들지 않으므로 이 검증의 결과가
	 * 경계 근처의 운에 좌우되지 않는다.
	 */
	@Test
	void formsAJudgeableWindowFromRealKafkaEventsAndDetectsOnIt() throws Exception {
		Instant windowEnd = MetricWindow.bucketStartOf(Instant.now());
		Instant oldestBucketStart = windowEnd.minusSeconds(WINDOW_SECONDS);

		publishTrafficInBuckets(HOT_PATH_SERIES, oldestBucketStart, MetricWindow.WINDOW_BUCKETS);

		await().atMost(METRIC_TIMEOUT)
			.pollInterval(POLL_INTERVAL)
			.untilAsserted(() -> assertThat(windowEndingAt(HOT_PATH_SERIES, windowEnd).requestCount())
				.isEqualTo(EXPECTED_WINDOW_REQUEST_COUNT));

		assertThat(windowEndingAt(HOT_PATH_SERIES, windowEnd)).satisfies((snapshot) -> {
			assertThat(snapshot.windowStart()).isEqualTo(oldestBucketStart);
			assertThat(snapshot.windowEnd()).isEqualTo(windowEnd);
			assertThat(snapshot.errorCount()).isZero();
			assertThat(snapshot.errorRate()).isZero();
			assertThat(snapshot.p95LatencyMs()).isEqualTo(HEALTHY_LATENCY_MS);
		});
		await().atMost(METRIC_TIMEOUT)
			.pollInterval(POLL_INTERVAL)
			.untilAsserted(() -> assertThat(statesOf(HOT_PATH_SERIES)).hasSize(IncidentType.values().length)
				.extracting(DetectionState::getType)
				.containsExactlyInAnyOrder(IncidentType.values()));
		// 판정이 본 창은 이 검증이 트래픽을 넣기 시작한 시점 이후에 닫힌 창이다. 그 중 어느 창이었는지는
		// 소비 속도에 달렸지만, 정상 트래픽이라 어느 창을 보았든 이상 연속은 시작되지 않는다.
		assertThat(statesOf(HOT_PATH_SERIES)).allSatisfy((state) -> {
			assertThat(state.getLastEvaluatedWindowEnd()).isAfterOrEqualTo(windowEnd);
			assertThat(state.getAbnormalConsecutive()).isZero();
		});
		assertThat(incidentsOf(HOT_PATH_SERIES)).isEmpty();
	}

	/**
	 * 실제 Redis에서 계산된 창이 두 번 연속 이상일 때 실제 MySQL에 Incident가 열리는지를 본다.
	 * <p>
	 * 창은 Production 쓰기 경로로 만든다. 테스트가 Redis Key에 직접 값을 넣으면 Key 형식이나 오류
	 * 분류가 어긋나도 이 검증은 통과하고, 그러면 확인하려던 것이 사라진다. 오류로 세어지는 근거가
	 * success가 아니라 5xx라는 판정도 이 경로를 지나야 실제로 적용된다.
	 * <p>
	 * 판정 기준 시각만 테스트가 정한다. Detector는 시각을 스스로 읽지 않으므로 이것은 Production 동작을
	 * 우회하는 것이 아니라 이미 열려 있는 계약을 쓰는 것이다.
	 */
	@Test
	void opensACriticalErrorRateIncidentOnTheSecondConsecutiveWindow() {
		Instant secondWindowEnd = MetricWindow.bucketStartOf(Instant.now());
		Instant firstWindowEnd = secondWindowEnd.minusSeconds(MetricWindow.BUCKET_SECONDS);
		// 두 창이 함께 덮는 Bucket을 모두 채운다. 연속된 두 창은 Bucket 하나만큼 어긋나므로 창 길이보다
		// 딱 하나 많다.
		recordErrorTrafficInBuckets(INCIDENT_SERIES, firstWindowEnd.minusSeconds(WINDOW_SECONDS),
				MetricWindow.WINDOW_BUCKETS + 1);

		assertCriticalWindow(INCIDENT_SERIES, firstWindowEnd);
		this.incidentDetector.detect(INCIDENT_SERIES, evaluationInsideBucketAfter(firstWindowEnd));

		assertThat(state(INCIDENT_SERIES, IncidentType.ERROR_RATE)).satisfies((state) -> {
			assertThat(state.getLastEvaluatedWindowEnd()).isEqualTo(firstWindowEnd);
			assertThat(state.getAbnormalConsecutive()).isEqualTo(1);
			assertThat(state.getRecoveryConsecutive()).isZero();
		});
		assertThat(incidentsOf(INCIDENT_SERIES)).isEmpty();

		assertCriticalWindow(INCIDENT_SERIES, secondWindowEnd);
		this.incidentDetector.detect(INCIDENT_SERIES, evaluationInsideBucketAfter(secondWindowEnd));

		assertThat(state(INCIDENT_SERIES, IncidentType.ERROR_RATE)).satisfies((state) -> {
			assertThat(state.getLastEvaluatedWindowEnd()).isEqualTo(secondWindowEnd);
			assertThat(state.getAbnormalConsecutive()).isEqualTo(2);
			assertThat(state.getRecoveryConsecutive()).isZero();
		});
		assertThat(incidentsOf(INCIDENT_SERIES)).singleElement().satisfies((incident) -> {
			assertThat(incident.getService()).isEqualTo(SERVICE);
			assertThat(incident.getMethod()).isEqualTo(METHOD);
			assertThat(incident.getEndpoint()).isEqualTo(INCIDENT_SERIES.endpoint());
			assertThat(incident.getType()).isEqualTo(IncidentType.ERROR_RATE);
			assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
			assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
			assertThat(incident.getOpenedAt()).isEqualTo(secondWindowEnd);
			assertThat(incident.getResolvedAt()).isNull();
			// 근거로 남는 것은 연속을 완성한 두 번째 창이다. 첫 창의 값이 남으면 Incident가 열린 시각과
			// 그 안의 숫자가 서로 다른 구간을 가리킨다.
			assertThat(incident.getTriggerWindowStart()).isEqualTo(secondWindowEnd.minusSeconds(WINDOW_SECONDS));
			assertThat(incident.getTriggerWindowEnd()).isEqualTo(secondWindowEnd);
			assertThat(incident.getTriggerRequestCount()).isEqualTo(EXPECTED_WINDOW_REQUEST_COUNT);
			assertThat(incident.getTriggerErrorCount()).isEqualTo(EXPECTED_WINDOW_REQUEST_COUNT);
			assertThat(incident.getTriggerErrorRate()).isEqualByComparingTo("100.00");
			assertThat(incident.getTriggerP95LatencyMs()).isEqualTo(HEALTHY_LATENCY_MS);
		});
	}

	/**
	 * 같은 트래픽에서 응답 시간은 정상으로 판정되므로 그쪽 Incident는 열리지 않는다. 두 종류가 하나의
	 * 판정으로 뭉쳐 있으면 오류만 난 계열에 응답 시간 Incident까지 함께 열린다.
	 */
	@Test
	void doesNotOpenALatencyIncidentFromTheSameErrorTraffic() {
		Instant secondWindowEnd = MetricWindow.bucketStartOf(Instant.now());
		Instant firstWindowEnd = secondWindowEnd.minusSeconds(MetricWindow.BUCKET_SECONDS);
		recordErrorTrafficInBuckets(INCIDENT_SERIES, firstWindowEnd.minusSeconds(WINDOW_SECONDS),
				MetricWindow.WINDOW_BUCKETS + 1);

		this.incidentDetector.detect(INCIDENT_SERIES, evaluationInsideBucketAfter(firstWindowEnd));
		this.incidentDetector.detect(INCIDENT_SERIES, evaluationInsideBucketAfter(secondWindowEnd));

		assertThat(state(INCIDENT_SERIES, IncidentType.LATENCY)).satisfies((state) -> {
			assertThat(state.getLastEvaluatedWindowEnd()).isEqualTo(secondWindowEnd);
			assertThat(state.getAbnormalConsecutive()).isZero();
			assertThat(state.getRecoveryConsecutive()).isEqualTo(2);
		});
		assertThat(incidentsOf(INCIDENT_SERIES)).extracting(Incident::getType)
			.containsExactly(IncidentType.ERROR_RATE);
	}

	/**
	 * 이상이 계속돼도 기록은 하나다. 이미 열린 Incident가 있는데 다음 창에서 또 열리면 같은 장애가 창
	 * 수만큼 늘어나 무엇이 진짜인지 알 수 없게 된다.
	 * <p>
	 * 세 번째 창까지 볼 수 있도록 Bucket을 하나 더 채운다. 연속 횟수는 2에서 멈추므로 세 번째 창은 이미
	 * 열린 Incident를 그대로 두는 것이 맞다.
	 */
	@Test
	void keepsASingleIncidentWhileTheCriticalWindowsContinue() {
		Instant thirdWindowEnd = MetricWindow.bucketStartOf(Instant.now());
		Instant secondWindowEnd = thirdWindowEnd.minusSeconds(MetricWindow.BUCKET_SECONDS);
		Instant firstWindowEnd = secondWindowEnd.minusSeconds(MetricWindow.BUCKET_SECONDS);
		recordErrorTrafficInBuckets(CONTINUED_SERIES, firstWindowEnd.minusSeconds(WINDOW_SECONDS),
				MetricWindow.WINDOW_BUCKETS + 2);

		this.incidentDetector.detect(CONTINUED_SERIES, evaluationInsideBucketAfter(firstWindowEnd));
		this.incidentDetector.detect(CONTINUED_SERIES, evaluationInsideBucketAfter(secondWindowEnd));
		Long openedId = incidentsOf(CONTINUED_SERIES).getFirst().getId();

		assertCriticalWindow(CONTINUED_SERIES, thirdWindowEnd);
		this.incidentDetector.detect(CONTINUED_SERIES, evaluationInsideBucketAfter(thirdWindowEnd));

		assertThat(state(CONTINUED_SERIES, IncidentType.ERROR_RATE)).satisfies((state) -> {
			assertThat(state.getLastEvaluatedWindowEnd()).isEqualTo(thirdWindowEnd);
			assertThat(state.getAbnormalConsecutive()).isEqualTo(2);
		});
		assertThat(incidentsOf(CONTINUED_SERIES)).singleElement().satisfies((incident) -> {
			assertThat(incident.getId()).isEqualTo(openedId);
			assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
			assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
			assertThat(incident.getOpenedAt()).isEqualTo(secondWindowEnd);
		});
	}

	// --- 창 fixture ---------------------------------------------------------

	/**
	 * 이미 닫힌 Bucket들을 Production 쓰기 경로로 채운다. 반영되지 않은 Event가 하나라도 있으면 이후
	 * 창의 값이 전부 어긋나므로 그 자리에서 실패시킨다.
	 */
	private void recordErrorTrafficInBuckets(MetricSeries series, Instant oldestBucketStart, int bucketCount) {
		for (int bucketIndex = 0; bucketIndex < bucketCount; bucketIndex++) {
			Instant occurredAt = occurredAtInBucket(oldestBucketStart, bucketIndex);
			for (int requestIndex = 0; requestIndex < REQUESTS_PER_BUCKET; requestIndex++) {
				String eventId = newEventId();
				rememberKeys(series, eventId, occurredAt);
				assertThat(this.metricRecorder.record(event(series, eventId, occurredAt, ERROR_STATUS)))
					.as("Metric에 반영되지 않은 Event가 있으면 창의 값이 어긋난다. occurredAt=%s", occurredAt)
					.isEqualTo(MetricRecordOutcome.APPLIED);
			}
		}
	}

	/** 같은 Bucket 구성을 실제 Kafka로 보낸다. */
	private void publishTrafficInBuckets(MetricSeries series, Instant oldestBucketStart, int bucketCount)
			throws Exception {
		for (int bucketIndex = 0; bucketIndex < bucketCount; bucketIndex++) {
			Instant occurredAt = occurredAtInBucket(oldestBucketStart, bucketIndex);
			for (int requestIndex = 0; requestIndex < REQUESTS_PER_BUCKET; requestIndex++) {
				publish(series, newEventId(), occurredAt, SUCCESS_STATUS);
			}
		}
	}

	private static Instant occurredAtInBucket(Instant oldestBucketStart, int bucketIndex) {
		return oldestBucketStart.plusSeconds((long) bucketIndex * MetricWindow.BUCKET_SECONDS)
			.plusSeconds(SECONDS_INTO_THE_BUCKET);
	}

	/**
	 * 판정이 읽을 창을 같은 저장소에서 그대로 읽어 확인한다. Incident의 근거가 된 숫자가 실제 Redis
	 * Bucket에서 나온 것임을 판정 전에 남기는 자리이다.
	 */
	private void assertCriticalWindow(MetricSeries series, Instant windowEnd) {
		assertThat(windowEndingAt(series, windowEnd)).satisfies((snapshot) -> {
			assertThat(snapshot.windowStart()).isEqualTo(windowEnd.minusSeconds(WINDOW_SECONDS));
			assertThat(snapshot.windowEnd()).isEqualTo(windowEnd);
			assertThat(snapshot.requestCount()).isEqualTo(EXPECTED_WINDOW_REQUEST_COUNT);
			assertThat(snapshot.errorCount()).isEqualTo(EXPECTED_WINDOW_REQUEST_COUNT);
			assertThat(snapshot.errorRate()).isEqualTo(CRITICAL_ERROR_RATE_PERCENT);
			assertThat(snapshot.p95LatencyMs()).isEqualTo(HEALTHY_LATENCY_MS);
		});
	}

	/**
	 * 그 시각에 끝나는 창을 읽는다. Service는 기준 시각이 속한 Bucket까지 창에 넣으므로, 창의 마지막
	 * Bucket 시작을 넘기면 창의 끝이 정확히 windowEnd가 된다.
	 */
	private MetricSnapshot windowEndingAt(MetricSeries series, Instant windowEnd) {
		return this.rollingMetricService.snapshot(series, windowEnd.minusSeconds(MetricWindow.BUCKET_SECONDS));
	}

	/**
	 * 그 창이 막 닫히고 다음 Bucket이 채워지는 중인 시각이다. Detector는 기준 시각이 속한 Bucket을
	 * 판정에서 빼므로, 이 시각으로 부르면 평가 대상이 windowEnd에서 끝나는 창이 된다.
	 */
	private static Instant evaluationInsideBucketAfter(Instant windowEnd) {
		return windowEnd.plusSeconds(SECONDS_INTO_THE_FILLING_BUCKET);
	}

	// --- Event 헬퍼 ---------------------------------------------------------

	/**
	 * 응답 시간은 언제나 정상 범위이다. 이유는 {@link #HEALTHY_LATENCY_MS} 참고.
	 * <p>
	 * 5xx인 Event의 success는 false이지만, 오류로 세어지는 근거는 success가 아니라 httpStatus이다.
	 */
	private static TelemetryEvent event(MetricSeries series, String eventId, Instant occurredAt, int httpStatus) {
		boolean success = httpStatus < FIRST_FAILURE_STATUS;
		return new TelemetryEvent(eventId, 1, occurredAt, series.service(), SERVICE + "-01:8080", newEventId(),
				series.method(), series.endpoint(), httpStatus, success, HEALTHY_LATENCY_MS,
				success ? null : "E2E_FAULT", success ? null : "합성 장애");
	}

	private static String newEventId() {
		return UUID.randomUUID().toString();
	}

	// --- Kafka 헬퍼 ---------------------------------------------------------

	/**
	 * Demo Shop Producer가 쓰는 Key 형식으로 보낸다. 같은 계열의 Event가 같은 partition에 모여 발행
	 * 순서대로 처리된다.
	 */
	private void publish(MetricSeries series, String eventId, Instant occurredAt, int httpStatus) throws Exception {
		rememberKeys(series, eventId, occurredAt);
		String key = series.service() + ":" + series.endpoint();
		this.producer
			.send(new ProducerRecord<>(TelemetryEventListener.TOPIC, key,
					payload(series, eventId, occurredAt, httpStatus)))
			.get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
	}

	private Map<String, Object> producerConfig() {
		Map<String, Object> config = new HashMap<>();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, this.kafkaContainer.getBootstrapServers());
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		return config;
	}

	/**
	 * canonical fixture에서 계열과 결과에 해당하는 값만 바꾼다. 나머지 필드 구성은 v1 계약 그대로
	 * 남는다. 발생 시각은 반드시 덮어쓴다. fixture의 고정 시각은 집계 창 밖이라 그대로 두면
	 * MetricRecorder가 정상적으로 건너뛴다.
	 */
	private String payload(MetricSeries series, String eventId, Instant occurredAt, int httpStatus)
			throws IOException {
		return canonicalPayloadWith((event) -> {
			event.put("eventId", eventId);
			event.put("occurredAt", occurredAt.toString());
			event.put("service", series.service());
			event.put("method", series.method());
			event.put("endpoint", series.endpoint());
			event.put("httpStatus", httpStatus);
			// success는 v1 계약에서 httpStatus < 400과 같은 뜻이라, 어긋나면 Listener가 걸러 낸다.
			event.put("success", httpStatus < FIRST_FAILURE_STATUS);
			event.put("latencyMs", HEALTHY_LATENCY_MS);
		});
	}

	private String canonicalPayloadWith(Consumer<ObjectNode> change) throws IOException {
		ObjectNode payload = (ObjectNode) this.objectMapper.readTree(readCanonicalFixture());
		change.accept(payload);
		return this.objectMapper.writeValueAsString(payload);
	}

	private String readCanonicalFixture() throws IOException {
		try (InputStream fixture = new ClassPathResource(FIXTURE_PATH).getInputStream()) {
			return new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	// --- Detection 헬퍼 -----------------------------------------------------

	/**
	 * 계열로 걸러 읽는다. 하나의 Redis와 하나의 MySQL을 여러 검증이 함께 쓰므로, 전체를 세면 다른 검증이
	 * 남긴 것까지 함께 잡힌다. 계열마다 endpoint가 다르므로 그것으로 구분된다.
	 */
	private List<DetectionState> statesOf(MetricSeries series) {
		return this.detectionStateRepository.findAll()
			.stream()
			.filter((state) -> state.getEndpoint().equals(series.endpoint()))
			.toList();
	}

	private DetectionState state(MetricSeries series, IncidentType type) {
		return statesOf(series).stream()
			.filter((state) -> state.getType() == type)
			.findFirst()
			.orElseThrow(() -> new AssertionError("Detection State가 없다: type=" + type));
	}

	private List<Incident> incidentsOf(MetricSeries series) {
		return this.incidentRepository.findAll()
			.stream()
			.filter((incident) -> incident.getEndpoint().equals(series.endpoint()))
			.toList();
	}

	// --- Redis 헬퍼 ---------------------------------------------------------

	/**
	 * Key는 Phase 3-3에서 확정한 형식을 테스트가 직접 만든다. 저장소의 Key 생성은 private이며 그것을
	 * 테스트 때문에 열면, 형식이 바뀌어도 이 테스트는 계속 통과한다.
	 */
	private void rememberKeys(MetricSeries series, String eventId, Instant occurredAt) {
		this.writtenKeys.add("pulseops:dedup:event:" + eventId);
		this.writtenKeys.add(metricKey(series, occurredAt) + ":counts");
		this.writtenKeys.add(metricKey(series, occurredAt) + ":latency");
	}

	private static String metricKey(MetricSeries series, Instant occurredAt) {
		return "pulseops:metric:" + series.service() + ":" + series.method() + ":" + series.endpointKey() + ":"
				+ MetricWindow.bucketStartOf(occurredAt).getEpochSecond();
	}

	// --- 테스트 인프라 ------------------------------------------------------

	/**
	 * Kafka와 Redis를 함께 띄운다. Redis {@code name}이 없으면 GenericContainer가 Redis로 인식되지
	 * 않아 연결 정보가 Context에 등록되지 않는다. MySQL은 {@link MySqlTestcontainersConfiguration}이
	 * 맡는다.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class KafkaAndRedisTestcontainersConfiguration {

		@Bean
		@ServiceConnection
		KafkaContainer kafkaContainer() {
			return new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));
		}

		@Bean
		@ServiceConnection(name = "redis")
		GenericContainer<?> redisContainer() {
			return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(REDIS_PORT);
		}

		/**
		 * 테스트 fixture이다. PulseOps Consumer는 Topic owner가 아니므로 Production에는 NewTopic이
		 * 없고, Broker의 auto creation에 맡기면 Phase 2에서 정한 것과 다른 Topic 위에서 검증하게 된다.
		 */
		@Bean
		NewTopic telemetryTopic() {
			return TopicBuilder.name(TelemetryEventListener.TOPIC)
				.partitions(EXPECTED_PARTITIONS)
				.replicas(EXPECTED_REPLICAS)
				.build();
		}

	}

}

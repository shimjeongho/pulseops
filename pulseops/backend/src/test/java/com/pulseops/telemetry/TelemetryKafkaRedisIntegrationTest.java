package com.pulseops.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pulseops.MySqlTestcontainersConfiguration;
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
 * Demo Shop이 발행한 Record가 실제 Kafka와 실제 Redis를 지나 Metric으로 남는 경로 전체를 검증한다.
 * <p>
 * Kafka Record, Listener 검증, MetricRecorder, RedisMetricStore, Lua, Redis 사이에는 계층마다 이미
 * 자기 테스트가 있다. 여기서 확인하는 것은 그 계층들이 실제로 이어져 있는지, 그리고 Kafka가 같은
 * Record를 다시 전달했을 때 Redis dedup이 그 재전달을 실제로 막는지이다. 그래서 Kafka와 Redis를 모두
 * 진짜로 띄우고 Mock을 쓰지 않는다.
 * <p>
 * 이 경로는 exactly-once가 아니다. Consumer 처리는 at-least-once 성격이고 같은 eventId의 재전달만
 * Redis dedup이 막아 준다. Demo Shop Producer는 발행에 실패해도 요청을 살리는 fail-open이고 Redis
 * 장애 구간의 Event는 WARN 후 건너뛰므로, Metric에서 영구히 빠지는 Event가 있을 수 있다.
 * <p>
 * 각 검증은 자기만의 계열과 eventId를 쓰고 자기가 만든 Key만 지운다. 그래서 하나의 Topic과 하나의
 * Consumer를 공유해도 실행 순서에 의존하지 않는다.
 * <p>
 * MySQL은 Context에 Datasource가 있어 함께 띄운다. {@link MySqlTestcontainersConfiguration} 참고.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelemetryKafkaRedisIntegrationTest {

	private static final String KAFKA_IMAGE = "apache/kafka:4.0.0";

	private static final String REDIS_IMAGE = "redis:8.4.2-alpine";

	private static final int REDIS_PORT = 6379;

	/** Phase 2에서 확정한 Topic 구성이다. Broker의 auto creation에 맡기면 partition이 하나가 된다. */
	private static final int EXPECTED_PARTITIONS = 3;

	private static final int EXPECTED_REPLICAS = 1;

	private static final MetricSeries SINGLE_EVENT_SERIES = new MetricSeries("demo-shop", "GET",
			"/api/products/{productId}");

	private static final MetricSeries DUPLICATE_EVENT_SERIES = new MetricSeries("demo-shop", "GET",
			"/api/orders/{orderId}");

	private static final MetricSeries STATUS_MIX_SERIES = new MetricSeries("demo-shop", "POST", "/api/orders");

	private static final String FIXTURE_PATH = "telemetry/telemetry-event-v1.json";

	private static final String REQUEST_COUNT_FIELD = "requestCount";

	private static final String ERROR_COUNT_FIELD = "errorCount";

	private static final Duration METRIC_TIMEOUT = Duration.ofSeconds(60);

	private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

	private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private KafkaContainer kafkaContainer;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private RollingMetricService rollingMetricService;

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
	void removeWrittenKeys() {
		this.redisTemplate.delete(this.writtenKeys);
	}

	/** Kafka로 들어온 Event 하나가 Redis Bucket에 남기는 것 전부를 확인한다. */
	@Test
	void writesTheMetricOfAConsumedEventToRedis() throws Exception {
		Instant occurredAt = Instant.now();
		String eventId = newEventId();

		publish(SINGLE_EVENT_SERIES, eventId, occurredAt, 200, 37L);

		awaitRequestCount(SINGLE_EVENT_SERIES, occurredAt, "1");
		assertThat(counts(SINGLE_EVENT_SERIES, occurredAt, ERROR_COUNT_FIELD)).isEqualTo("0");
		assertThat(latencySamples(SINGLE_EVENT_SERIES, occurredAt)).containsExactly("37");
		assertThat(this.redisTemplate.hasKey(dedupKey(eventId))).isTrue();
	}

	/**
	 * Kafka는 같은 Record를 다시 전달할 수 있고 Consumer 처리는 at-least-once 성격이라, 재전달을 막는
	 * 것은 Redis dedup뿐이다.
	 * <p>
	 * 세 번째로 보내는 Event는 앞의 두 사본이 모두 처리됐다는 표시다. 같은 Key는 같은 partition으로
	 * 모이고 Consumer는 offset 순서대로 처리하므로, 이 Event의 응답 시간이 Redis에 보이면 앞의 두 사본은
	 * 이미 지나간 뒤다. requestCount가 2가 되기를 기다리면 두 번째 사본이 아직 처리되지 않은 중간
	 * 상태에서도 조건이 맞아 dedup 실패를 놓친다.
	 */
	@Test
	void appliesAKafkaRedeliveryOfTheSameEventOnlyOnce() throws Exception {
		Instant occurredAt = Instant.now();
		String redeliveredEventId = newEventId();

		publish(DUPLICATE_EVENT_SERIES, redeliveredEventId, occurredAt, 200, 37L);
		publish(DUPLICATE_EVENT_SERIES, redeliveredEventId, occurredAt, 200, 37L);
		publish(DUPLICATE_EVENT_SERIES, newEventId(), occurredAt, 200, 41L);

		await().atMost(METRIC_TIMEOUT)
			.pollInterval(POLL_INTERVAL)
			.untilAsserted(() -> assertThat(latencySamples(DUPLICATE_EVENT_SERIES, occurredAt)).contains("41"));
		assertThat(counts(DUPLICATE_EVENT_SERIES, occurredAt, REQUEST_COUNT_FIELD)).isEqualTo("2");
		assertThat(counts(DUPLICATE_EVENT_SERIES, occurredAt, ERROR_COUNT_FIELD)).isEqualTo("0");
		assertThat(latencySamples(DUPLICATE_EVENT_SERIES, occurredAt)).containsExactly("37", "41");
	}

	/**
	 * 5xx만 오류로 세고 4xx는 세지 않는다는 판정은 Listener가 아니라 MetricRecorder의 몫이다. 실제
	 * 경로에서도 그 판정이 적용되는지, 그리고 Bucket에 쌓인 값이 창 하나의 Snapshot으로 읽히는지를 함께
	 * 본다. 기준 시각으로 발생 시각을 그대로 넘겨, 테스트가 몇 초 걸려도 창이 밀리지 않게 한다.
	 */
	@Test
	void countsAServerErrorAsAnErrorInTheSnapshot() throws Exception {
		Instant occurredAt = Instant.now();

		publish(STATUS_MIX_SERIES, newEventId(), occurredAt, 200, 37L);
		publish(STATUS_MIX_SERIES, newEventId(), occurredAt, 500, 480L);

		awaitRequestCount(STATUS_MIX_SERIES, occurredAt, "2");
		MetricSnapshot snapshot = this.rollingMetricService.snapshot(STATUS_MIX_SERIES, occurredAt);
		assertThat(snapshot.requestCount()).isEqualTo(2);
		assertThat(snapshot.errorCount()).isEqualTo(1);
		assertThat(snapshot.errorRate()).isEqualTo(50.0);
	}

	// --- Kafka 헬퍼 ---------------------------------------------------------

	/**
	 * Demo Shop Producer가 쓰는 Key 형식으로 보낸다. 같은 계열의 Event가 같은 partition에 모여 발행
	 * 순서대로 처리된다.
	 */
	private void publish(MetricSeries series, String eventId, Instant occurredAt, int httpStatus, long latencyMs)
			throws Exception {
		this.writtenKeys.add(dedupKey(eventId));
		this.writtenKeys.add(countsKey(series, occurredAt));
		this.writtenKeys.add(latencyKey(series, occurredAt));
		String key = series.service() + ":" + series.endpoint();
		this.producer
			.send(new ProducerRecord<>(TelemetryEventListener.TOPIC, key,
					payload(series, eventId, occurredAt, httpStatus, latencyMs)))
			.get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
	}

	private Map<String, Object> producerConfig() {
		Map<String, Object> config = new HashMap<>();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, this.kafkaContainer.getBootstrapServers());
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		return config;
	}

	// --- Payload 헬퍼 -------------------------------------------------------

	/**
	 * canonical fixture에서 계열과 결과에 해당하는 값만 바꾼다. 나머지 필드 구성은 계약 그대로 남는다.
	 * <p>
	 * occurredAt은 반드시 현재 시각으로 덮어쓴다. fixture의 고정 시각은 집계 창 밖이라 MetricRecorder가
	 * 정상적으로 건너뛰며, 그러면 Redis에 아무것도 남지 않는다.
	 */
	private String payload(MetricSeries series, String eventId, Instant occurredAt, int httpStatus, long latencyMs)
			throws IOException {
		return canonicalPayloadWith((event) -> {
			event.put("eventId", eventId);
			event.put("occurredAt", occurredAt.toString());
			event.put("service", series.service());
			event.put("method", series.method());
			event.put("endpoint", series.endpoint());
			event.put("httpStatus", httpStatus);
			// success는 v1 계약에서 httpStatus < 400과 같은 뜻이라, 어긋나면 Listener가 걸러 낸다.
			event.put("success", httpStatus < 400);
			event.put("latencyMs", latencyMs);
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

	private static String newEventId() {
		return UUID.randomUUID().toString();
	}

	// --- Redis 헬퍼 ---------------------------------------------------------

	/** Listener는 비동기로 동작하므로 Redis에 값이 나타날 때까지 기다린다. */
	private void awaitRequestCount(MetricSeries series, Instant occurredAt, String expectedRequestCount) {
		await().atMost(METRIC_TIMEOUT)
			.pollInterval(POLL_INTERVAL)
			.untilAsserted(() -> assertThat(counts(series, occurredAt, REQUEST_COUNT_FIELD))
				.isEqualTo(expectedRequestCount));
	}

	private String counts(MetricSeries series, Instant occurredAt, String field) {
		return (String) this.redisTemplate.opsForHash().get(countsKey(series, occurredAt), field);
	}

	private List<String> latencySamples(MetricSeries series, Instant occurredAt) {
		return this.redisTemplate.opsForList().range(latencyKey(series, occurredAt), 0, -1);
	}

	/**
	 * Key는 Phase 3-3에서 확정한 형식을 테스트가 직접 만든다. 저장소의 Key 생성은 private이며 그것을
	 * 테스트 때문에 열면, 형식이 바뀌어도 이 테스트는 계속 통과한다. 계열 표기와 Bucket 경계는 계산이
	 * 아니라 계약이므로 Domain API를 그대로 쓴다.
	 */
	private static String countsKey(MetricSeries series, Instant occurredAt) {
		return metricKey(series, occurredAt) + ":counts";
	}

	private static String latencyKey(MetricSeries series, Instant occurredAt) {
		return metricKey(series, occurredAt) + ":latency";
	}

	private static String metricKey(MetricSeries series, Instant occurredAt) {
		return "pulseops:metric:" + series.service() + ":" + series.method() + ":" + series.endpointKey() + ":"
				+ MetricWindow.bucketStartOf(occurredAt).getEpochSecond();
	}

	private static String dedupKey(String eventId) {
		return "pulseops:dedup:event:" + eventId;
	}

	// --- 테스트 인프라 ------------------------------------------------------

	/**
	 * 이 테스트에서만 Kafka와 Redis를 함께 띄운다. 다른 통합 테스트는 자기에게 필요한 Container 하나만
	 * 쓰는 기존 구성을 유지한다. Redis {@code name}이 없으면 GenericContainer가 Redis로 인식되지 않아
	 * 연결 정보가 Context에 등록되지 않는다.
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

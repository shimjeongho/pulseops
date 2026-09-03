package com.pulseops.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.incident.DetectionStateRepository;
import com.pulseops.incident.IncidentDetector;
import com.pulseops.incident.IncidentRepository;
import com.pulseops.metric.MetricSeries;
import com.pulseops.metric.MetricWindow;
import com.pulseops.metric.RedisMetricStore;
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
import java.util.function.Consumer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Consumer가 처리에 실패했을 때 무엇이 일어나는지를 실제 Kafka, Redis, MySQL 위에서 검증한다.
 * <p>
 * 여기서 확인하는 것은 집계 값이 아니라 실패를 다루는 방식이다. 저장소가 잠깐 흔들렸을 때 같은
 * Event를 다시 처리할 수 있는지, 몇 번까지 다시 시도하는지, 그래도 안 되면 어디에 남는지, 그리고 그
 * 과정에서 Metric이 두 번 세어지지 않는지가 전부다.
 * <p>
 * 실패는 실제 Bean을 감싼 spy로 만든다. Production Code에 "다음 호출을 실패시켜라" 같은 시험용 통로를
 * 두면 그 통로 자체가 운영에서 켜질 수 있는 위험이 된다. Container를 실제로 멈추는 방법은 재현이
 * 불안정하고 어느 시점에 끊겼는지를 테스트가 정할 수 없다.
 * <p>
 * 이 경로는 exactly-once가 아니다. Demo Shop Producer는 발행 실패 시 요청을 살리는 fail-open이라 애초에
 * Broker에 닿지 못한 Event가 있을 수 있고, 여기서 보장하는 것은 Broker에 들어온 Record의 처리뿐이다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelemetryConsumerReliabilityIntegrationTest {

	private static final String KAFKA_IMAGE = "apache/kafka:4.0.0";

	private static final String REDIS_IMAGE = "redis:8.4.2-alpine";

	private static final int REDIS_PORT = 6379;

	private static final int TOPIC_PARTITIONS = 3;

	private static final short TOPIC_REPLICAS = 1;

	private static final String FIXTURE_PATH = "telemetry/telemetry-event-v1.json";

	private static final String REQUEST_COUNT_FIELD = "requestCount";

	/** 설정한 재시도 예산이다. 최초 시도 1회에 재시도 2회를 더한 값이며 이 수를 테스트가 못 박는다. */
	private static final int MAX_PROCESSING_ATTEMPTS = 3;

	private static final Duration TIMEOUT = Duration.ofSeconds(60);

	private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

	private static final Duration DEAD_LETTER_POLL = Duration.ofMillis(500);

	private static final MetricSeries REDIS_RETRY_SERIES = new MetricSeries("demo-shop", "GET", "/api/reliability/redis");

	private static final MetricSeries RESUME_SERIES = new MetricSeries("demo-shop", "GET", "/api/reliability/resume");

	private static final MetricSeries EXHAUSTION_SERIES = new MetricSeries("demo-shop", "GET",
			"/api/reliability/exhaustion");

	private static final MetricSeries POISON_SERIES = new MetricSeries("demo-shop", "GET", "/api/reliability/poison");

	private static final MetricSeries COMMITTED_SERIES = new MetricSeries("demo-shop", "GET",
			"/api/reliability/committed");

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private KafkaContainer kafkaContainer;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private DetectionStateRepository detectionStateRepository;

	@Autowired
	private IncidentRepository incidentRepository;

	@MockitoSpyBean
	private RedisMetricStore metricStore;

	@MockitoSpyBean
	private IncidentDetector incidentDetector;

	@MockitoSpyBean
	private TelemetryEventValidator validator;

	private KafkaProducer<String, String> producer;

	private KafkaConsumer<String, String> deadLetterConsumer;

	private final Set<String> writtenKeys = new HashSet<>();

	@BeforeAll
	void openKafkaClients() {
		this.producer = new KafkaProducer<>(producerConfig());
		this.deadLetterConsumer = new KafkaConsumer<>(deadLetterConsumerConfig());
		this.deadLetterConsumer.assign(deadLetterPartitions());
	}

	@AfterAll
	void closeKafkaClients() {
		this.producer.close();
		this.deadLetterConsumer.close();
	}

	/**
	 * 앞 검증이 남긴 DLT Record를 보지 않도록 매번 끝으로 이동한다.
	 * <p>
	 * seekToEnd는 예약일 뿐이라 다음 poll에서야 실제 위치가 정해진다. 그대로 두면 이번 검증이 만든
	 * Record가 도착한 뒤에 위치가 정해져 그 Record를 건너뛴다. position을 읽어 지금 위치를 확정한다.
	 */
	@BeforeEach
	void skipEarlierDeadLetterRecords() {
		List<TopicPartition> partitions = deadLetterPartitions();
		this.deadLetterConsumer.seekToEnd(partitions);
		partitions.forEach(this.deadLetterConsumer::position);
	}

	@AfterEach
	void removeWrittenKeysAndDetectionResults() {
		this.redisTemplate.delete(this.writtenKeys);
		this.writtenKeys.clear();
		this.incidentRepository.deleteAll();
		this.detectionStateRepository.deleteAll();
	}

	/**
	 * Redis가 잠깐 응답하지 않아도 Event를 잃지 않는다. Phase 3에서는 이 Event가 WARN 한 줄과 함께
	 * 사라졌지만, 이제는 실패가 재시도로 이어져 같은 Event가 다시 반영된다.
	 */
	@Test
	void retriesAndSucceedsAfterATransientRedisFailure() throws Exception {
		Instant occurredAt = Instant.now();
		String eventId = newEventId();
		willThrow(new RedisConnectionFailureException("Redis에 연결할 수 없다")).willCallRealMethod()
			.given(this.metricStore)
			.record(any(), any(), any(), anyBoolean(), anyLong());

		publish(REDIS_RETRY_SERIES, eventId, occurredAt, 200, 37L);

		awaitRequestCount(REDIS_RETRY_SERIES, occurredAt, "1");
		then(this.metricStore).should(times(2)).record(any(), any(), any(), anyBoolean(), anyLong());
		assertThat(pollDeadLetterRecords()).isEmpty();
	}

	/**
	 * Phase 7의 핵심이다.
	 * <p>
	 * 첫 시도가 Redis 반영까지 끝낸 뒤 판정에서 실패하면, 재시도로 온 같은 Event는 Redis에서 중복으로
	 * 걸린다. 그때 "달라진 것이 없으니 할 일도 없다"고 끝내면 첫 시도가 남긴 미완의 판정을 아무도
	 * 이어받지 못한다. 그래서 중복이어도 판정은 이어져야 하고, 그러면서도 Metric은 두 번 세어지면 안
	 * 된다. 두 가지를 한자리에서 확인한다.
	 */
	@Test
	void resumesTheEvaluationOnRetryWhenTheMetricWasAlreadyApplied() throws Exception {
		Instant occurredAt = Instant.now();
		String eventId = newEventId();
		willThrow(new QueryTimeoutException("MySQL이 응답하지 않는다")).willCallRealMethod()
			.given(this.incidentDetector)
			.detect(any(), any());

		publish(RESUME_SERIES, eventId, occurredAt, 200, 37L);

		// 판정이 실제로 끝났다는 증거는 Detection State가 남았다는 사실이다. 첫 시도만으로는 남지 않는다.
		await().atMost(TIMEOUT)
			.pollInterval(POLL_INTERVAL)
			.untilAsserted(() -> assertThat(detectionStatesOf(RESUME_SERIES)).isNotEmpty());
		then(this.incidentDetector).should(times(2)).detect(any(), any());
		// 재시도가 Metric을 다시 세지 않았다. Redis dedup이 두 번째 반영을 막는다.
		assertThat(requestCount(RESUME_SERIES, occurredAt)).isEqualTo("1");
		assertThat(pollDeadLetterRecords()).isEmpty();
	}

	/**
	 * 계속 실패하는 Event는 정해진 횟수만 시도하고 DLT로 격리한다. 무한히 다시 시도하면 그 partition의
	 * 뒤 Record가 영원히 처리되지 않는다.
	 */
	@Test
	void sendsToTheDeadLetterTopicAfterTheRetryBudgetIsExhausted() throws Exception {
		Instant occurredAt = Instant.now();
		String eventId = newEventId();
		willThrow(new QueryTimeoutException("MySQL이 계속 응답하지 않는다")).given(this.incidentDetector)
			.detect(any(), any());

		publish(EXHAUSTION_SERIES, eventId, occurredAt, 200, 37L);

		List<ConsumerRecord<String, String>> deadLettered = awaitDeadLetterRecords(1);
		assertThat(deadLettered).hasSize(1);
		assertThat(deadLettered.get(0).value()).contains(eventId);
		then(this.incidentDetector).should(times(MAX_PROCESSING_ATTEMPTS)).detect(any(), any());
		// 세 번을 시도해도 Metric은 첫 시도에서 한 번만 반영된다.
		assertThat(requestCount(EXHAUSTION_SERIES, occurredAt)).isEqualTo("1");
	}

	/**
	 * 계약을 어긴 Event는 다시 읽어도 결과가 같다. 재시도에 시간을 쓰지 않고 곧바로 격리한다.
	 * <p>
	 * 시도 횟수는 validator 호출 수로 센다. 재시도가 있었다면 같은 Record에 대해 여러 번 불렸을 것이다.
	 */
	@Test
	void sendsAContractViolationStraightToTheDeadLetterTopicWithoutRetrying() throws Exception {
		String eventId = newEventId();
		String payload = canonicalPayloadWith((event) -> {
			event.put("eventId", eventId);
			event.put("occurredAt", Instant.now().toString());
			event.put("schemaVersion", 99);
		});

		send(POISON_SERIES, payload);

		List<ConsumerRecord<String, String>> deadLettered = awaitDeadLetterRecords(1);
		assertThat(deadLettered).hasSize(1);
		assertThat(deadLettered.get(0).value()).contains(eventId);
		then(this.validator).should(times(1)).validate(any());
	}

	/**
	 * DLT Record만 보고도 원본을 다시 찾을 수 있어야 한다. 값을 새로 담지 않고 Kafka 좌표만 남긴다.
	 */
	@Test
	void preservesTheOriginalCoordinatesInTheDeadLetterHeaders() throws Exception {
		String payload = canonicalPayloadWith((event) -> {
			event.put("eventId", newEventId());
			event.put("occurredAt", Instant.now().toString());
			event.put("schemaVersion", 99);
		});

		send(POISON_SERIES, payload);

		ConsumerRecord<String, String> deadLettered = awaitDeadLetterRecords(1).get(0);
		assertThat(header(deadLettered, "kafka_dlt-original-topic")).isEqualTo(TelemetryEventListener.TOPIC);
		assertThat(header(deadLettered, "kafka_dlt-original-partition")).isNotNull();
		assertThat(header(deadLettered, "kafka_dlt-original-offset")).isNotNull();
		// Spring Kafka는 Listener에서 나온 예외를 자기 Type으로 감싸므로 실패의 정체는 cause에 남는다.
		assertThat(header(deadLettered, "kafka_dlt-exception-cause-fqcn"))
			.isEqualTo(TelemetryEventContractException.class.getName());
		// 사유는 남기되 값은 남기지 않는다. 이 Header가 값 노출 경로가 되면 안 된다.
		assertThat(header(deadLettered, "kafka_dlt-exception-message")).contains("schemaVersion")
			.doesNotContain("이미 결제가 완료된 주문입니다.");
	}

	/**
	 * 격리한 Record 때문에 partition이 멈추면 안 된다. 같은 Key를 써서 두 Record를 같은 partition에
	 * 보내고, 뒤 Record가 처리되는지 본다.
	 */
	@Test
	void keepsConsumingTheNextValidRecordAfterOneGoesToTheDeadLetterTopic() throws Exception {
		Instant occurredAt = Instant.now();
		String poison = canonicalPayloadWith((event) -> {
			event.put("eventId", newEventId());
			event.put("occurredAt", occurredAt.toString());
			event.put("schemaVersion", 99);
		});

		send(POISON_SERIES, poison);
		publish(POISON_SERIES, newEventId(), occurredAt, 200, 41L);

		awaitRequestCount(POISON_SERIES, occurredAt, "1");
		assertThat(awaitDeadLetterRecords(1)).hasSize(1);
	}

	/**
	 * 판정이 MySQL에 commit된 뒤에 그 시도가 실패해도, 재시도가 같은 창을 다시 진행시키면 안 된다.
	 * cursor가 그것을 막는다.
	 * <p>
	 * 재시도 후에도 계열의 상태 행은 종류마다 하나뿐이고 cursor는 한 창에 머문다.
	 */
	@Test
	void doesNotProgressTheEvaluationTwiceWhenTheAttemptFailsAfterTheCommit() throws Exception {
		Instant occurredAt = Instant.now();
		willAnswer((invocation) -> {
			invocation.callRealMethod();
			throw new QueryTimeoutException("commit 뒤에 끊겼다");
		}).willCallRealMethod().given(this.incidentDetector).detect(any(), any());

		publish(COMMITTED_SERIES, newEventId(), occurredAt, 200, 37L);

		// 상태는 첫 시도에서 이미 commit되므로 그것만 기다리면 재시도 전에 확인하게 된다. 재시도가
		// 실제로 한 번 더 판정을 부른 뒤에 세어야 중복 진행 여부를 볼 수 있다.
		await().atMost(TIMEOUT)
			.pollInterval(POLL_INTERVAL)
			.untilAsserted(() -> then(this.incidentDetector).should(times(2)).detect(any(), any()));
		// 종류마다 하나씩만 있어야 한다. 재시도가 상태를 새로 만들었다면 여기서 늘어난다.
		assertThat(detectionStatesOf(COMMITTED_SERIES)).hasSize(2);
		assertThat(detectionStatesOf(COMMITTED_SERIES)).allSatisfy((state) -> {
			assertThat(state.getAbnormalConsecutive()).isZero();
			assertThat(state.getRecoveryConsecutive()).isZero();
		});
		assertThat(this.incidentRepository.findAll()).isEmpty();
		assertThat(pollDeadLetterRecords()).isEmpty();
	}

	// --- Kafka 헬퍼 ---------------------------------------------------------

	private void publish(MetricSeries series, String eventId, Instant occurredAt, int httpStatus, long latencyMs)
			throws Exception {
		rememberKeys(series, eventId, occurredAt);
		send(series, payload(series, eventId, occurredAt, httpStatus, latencyMs));
	}

	/** Key는 Demo Shop이 쓰는 형식 그대로다. 같은 계열이 같은 partition으로 모여야 순서를 볼 수 있다. */
	private void send(MetricSeries series, String payload) throws Exception {
		String key = series.service() + ":" + series.endpoint();
		this.producer.send(new ProducerRecord<>(TelemetryEventListener.TOPIC, key, payload)).get();
	}

	private List<ConsumerRecord<String, String>> awaitDeadLetterRecords(int expectedCount) {
		List<ConsumerRecord<String, String>> collected = new java.util.ArrayList<>();
		await().atMost(TIMEOUT).pollInterval(POLL_INTERVAL).untilAsserted(() -> {
			collected.addAll(pollDeadLetterRecords());
			assertThat(collected).hasSize(expectedCount);
		});
		return collected;
	}

	/**
	 * 아무것도 오지 않아야 하는 검증에서는 잠깐 기다렸다가 확인한다. 곧바로 읽으면 아직 도착하지 않은
	 * 것과 오지 않을 것을 구분하지 못한다.
	 */
	private List<ConsumerRecord<String, String>> pollDeadLetterRecords() {
		ConsumerRecords<String, String> polled = this.deadLetterConsumer.poll(DEAD_LETTER_POLL);
		List<ConsumerRecord<String, String>> records = new java.util.ArrayList<>();
		polled.forEach(records::add);
		return records;
	}

	private static String header(ConsumerRecord<String, String> record, String name) {
		Header header = record.headers().lastHeader(name);
		if (header == null) {
			return null;
		}
		// original-topic과 exception-fqcn은 문자열이고, partition/offset은 4/8 byte 정수다. 여기서는
		// 존재 여부와 문자열 값만 확인하므로 문자열로 읽을 수 있는 것만 그대로 돌려준다.
		return new String(header.value(), StandardCharsets.UTF_8);
	}

	private List<TopicPartition> deadLetterPartitions() {
		return java.util.stream.IntStream.range(0, TOPIC_PARTITIONS)
			.mapToObj((partition) -> new TopicPartition(TelemetryConsumerConfiguration.DEAD_LETTER_TOPIC, partition))
			.toList();
	}

	private Map<String, Object> producerConfig() {
		Map<String, Object> config = new HashMap<>();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, this.kafkaContainer.getBootstrapServers());
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		return config;
	}

	private Map<String, Object> deadLetterConsumerConfig() {
		Map<String, Object> config = new HashMap<>();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, this.kafkaContainer.getBootstrapServers());
		config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		config.put(ConsumerConfig.GROUP_ID_CONFIG, "reliability-test-dlt-" + UUID.randomUUID());
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		return config;
	}

	// --- Payload 헬퍼 -------------------------------------------------------

	private String payload(MetricSeries series, String eventId, Instant occurredAt, int httpStatus, long latencyMs)
			throws IOException {
		return canonicalPayloadWith((event) -> {
			event.put("eventId", eventId);
			event.put("occurredAt", occurredAt.toString());
			event.put("service", series.service());
			event.put("method", series.method());
			event.put("endpoint", series.endpoint());
			event.put("httpStatus", httpStatus);
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

	// --- 상태 헬퍼 ----------------------------------------------------------

	private List<com.pulseops.incident.DetectionState> detectionStatesOf(MetricSeries series) {
		return this.detectionStateRepository.findAll()
			.stream()
			.filter((state) -> state.getService().equals(series.service())
					&& state.getMethod().equals(series.method()) && state.getEndpoint().equals(series.endpoint()))
			.toList();
	}

	private void awaitRequestCount(MetricSeries series, Instant occurredAt, String expected) {
		await().atMost(TIMEOUT)
			.pollInterval(POLL_INTERVAL)
			.untilAsserted(() -> assertThat(requestCount(series, occurredAt)).isEqualTo(expected));
	}

	private String requestCount(MetricSeries series, Instant occurredAt) {
		return (String) this.redisTemplate.opsForHash().get(countsKey(series, occurredAt), REQUEST_COUNT_FIELD);
	}

	private void rememberKeys(MetricSeries series, String eventId, Instant occurredAt) {
		this.writtenKeys.add(countsKey(series, occurredAt));
		this.writtenKeys.add(metricKey(series, occurredAt) + ":latency");
		this.writtenKeys.add("pulseops:dedup:event:" + eventId);
	}

	private static String countsKey(MetricSeries series, Instant occurredAt) {
		return metricKey(series, occurredAt) + ":counts";
	}

	private static String metricKey(MetricSeries series, Instant occurredAt) {
		return "pulseops:metric:" + series.service() + ":" + series.method() + ":" + series.endpointKey() + ":"
				+ MetricWindow.bucketStartOf(occurredAt).getEpochSecond();
	}

	// --- 테스트 인프라 ------------------------------------------------------

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

		/** 원본 Topic은 Demo Shop이 만든다. Consumer 쪽 테스트에서는 fixture로 세운다. */
		@Bean
		NewTopic telemetryTopic() {
			return TopicBuilder.name(TelemetryEventListener.TOPIC)
				.partitions(TOPIC_PARTITIONS)
				.replicas(TOPIC_REPLICAS)
				.build();
		}

	}

}

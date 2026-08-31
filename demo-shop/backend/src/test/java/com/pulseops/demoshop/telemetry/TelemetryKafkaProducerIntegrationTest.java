package com.pulseops.demoshop.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseops.demoshop.TestcontainersConfiguration;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP Request 한 건이 실제 Kafka Broker의 Telemetry Topic에 어떤 Record로 남는지 검증한다.
 * <p>
 * Demo Shop과 PulseOps는 Java Model이 아니라 Topic 위의 Record로만 연결되므로, Producer 설정이나
 * 직렬화 방식이 바뀌어 계약이 깨지면 Consumer가 아니라 여기서 먼저 드러나야 한다. Embedded Broker
 * 대신 실제 Broker를 쓰는 이유도 partition 수처럼 Broker가 실제로 만드는 결과를 확인하기 위해서다.
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = { "pulseops.telemetry.enabled=true",
		"pulseops.telemetry.service-instance=demo-shop-kafka-test" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelemetryKafkaProducerIntegrationTest {

	private static final String KAFKA_IMAGE = "apache/kafka:4.0.0";

	private static final int EXPECTED_PARTITIONS = 3;

	private static final long SEEDED_PRODUCT_ID = 1L;

	private static final String PRODUCT_ROUTE_TEMPLATE = "/api/products/{productId}";

	private static final String PRODUCT_EVENT_KEY = "demo-shop:" + PRODUCT_ROUTE_TEMPLATE;

	private static final Duration RECORD_TIMEOUT = Duration.ofSeconds(30);

	private static final int WARM_UP_ATTEMPTS = 5;

	private static final List<String> V1_FIELDS = List.of("eventId", "schemaVersion", "occurredAt", "service",
			"serviceInstance", "requestId", "method", "endpoint", "httpStatus", "success", "latencyMs", "errorType",
			"errorMessage");

	/** Record에 실리면 안 되는 내용이다. Event가 확장되더라도 여기서 먼저 막는다. */
	private static final List<String> FORBIDDEN_CONTENT = List.of("password", "authorization", "cookie", "bearer",
			"jwt", "apikey", "stacktrace", "select ");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ProducerFactory<?, ?> producerFactory;

	@Autowired
	private KafkaContainer kafkaContainer;

	private KafkaConsumer<String, String> consumer;

	private final Deque<ConsumerRecord<String, String>> received = new ArrayDeque<>();

	/**
	 * 하나의 Consumer로 Class 전체 Record를 순서대로 읽는다. 각 테스트가 Consumer를 새로 만들면
	 * 앞선 테스트가 만든 Record를 다시 읽게 되어 검증이 서로 얽힌다.
	 */
	@BeforeAll
	void prepareTheTelemetryPipeline() throws Exception {
		this.consumer = new KafkaConsumer<>(consumerConfig());
		this.consumer.subscribe(List.of(TelemetryPublisher.TOPIC));
		this.consumer.poll(Duration.ofSeconds(10));
		this.consumer.seekToEnd(this.consumer.assignment());
		warmUpTheProducer();
	}

	/**
	 * Producer는 첫 전송 때 만들어지며 그 자리에서 metadata를 받아 온다. max.block.ms를 짧게 정한
	 * 결과로 이 첫 전송은 metadata를 제때 얻지 못해 실패할 수 있다. Broker 사정 때문에 관측 Event
	 * 한 건을 잃는 것은 Demo Shop API 응답 시간을 지키기 위해 감수한 선택이지만, 그 타이밍 때문에
	 * 테스트 결과가 흔들려서는 안 되므로 검증 전에 Record가 실제로 도착하는 것을 확인해 둔다.
	 */
	private void warmUpTheProducer() throws Exception {
		for (int attempt = 0; attempt < WARM_UP_ATTEMPTS; attempt++) {
			this.mockMvc.perform(get(PRODUCT_ROUTE_TEMPLATE, SEEDED_PRODUCT_ID)).andExpect(status().isOk());
			drainInto(this.received, Duration.ofSeconds(5));
			if (!this.received.isEmpty()) {
				this.received.clear();
				return;
			}
		}
		throw new IllegalStateException("Kafka 전송 경로가 준비되지 않았다.");
	}

	@AfterAll
	void closeConsumer() {
		this.consumer.close();
	}

	// --- Topic --------------------------------------------------------------

	/**
	 * Broker의 auto creation에 맡기지 않고 partition 수를 우리가 정한다는 사실을 실제 Broker
	 * metadata로 확인한다.
	 */
	@Test
	void createsTheTelemetryTopicWithTheConfiguredPartitionCount() throws Exception {
		try (AdminClient admin = AdminClient.create(
				Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, this.kafkaContainer.getBootstrapServers()))) {
			assertThat(admin.describeTopics(List.of(TelemetryPublisher.TOPIC))
				.allTopicNames()
				.get()
				.get(TelemetryPublisher.TOPIC)
				.partitions()).hasSize(EXPECTED_PARTITIONS);
		}
	}

	// --- Producer 설정 ------------------------------------------------------

	/**
	 * Broker가 느려질 때 Demo Shop API가 함께 느려지지 않도록 정한 값이 실제 Producer에
	 * 적용됐는지 확인한다. 시간을 재는 대신 설정 자체를 보므로 결과가 환경에 흔들리지 않는다.
	 */
	@Test
	void appliesTheConfiguredProducerLimits() {
		Map<String, Object> config = this.producerFactory.getConfigurationProperties();

		assertThat(config).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, "500")
			.containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "10000")
			.containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
	}

	// --- Record -------------------------------------------------------------

	@Test
	void publishesTheApiRequestAsATelemetryRecord() throws Exception {
		String requestId = this.mockMvc.perform(get(PRODUCT_ROUTE_TEMPLATE, SEEDED_PRODUCT_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value((int) SEEDED_PRODUCT_ID))
			.andReturn()
			.getResponse()
			.getHeader(RequestIdFilter.REQUEST_ID_HEADER);

		ConsumerRecord<String, String> published = nextRecord();
		assertThat(published.topic()).isEqualTo(TelemetryPublisher.TOPIC);
		assertThat(published.key()).isEqualTo(PRODUCT_EVENT_KEY);

		JsonNode event = this.objectMapper.readTree(published.value());
		assertThat(event.propertyNames()).containsExactlyInAnyOrderElementsOf(V1_FIELDS);
		assertThat(event.size()).isEqualTo(13);
		assertThat(event.get("schemaVersion").asInt()).isEqualTo(1);
		assertThat(event.get("service").asString()).isEqualTo("demo-shop");
		assertThat(event.get("serviceInstance").asString()).isEqualTo("demo-shop-kafka-test");
		assertThat(event.get("endpoint").asString()).isEqualTo(PRODUCT_ROUTE_TEMPLATE);
		assertThat(event.get("method").asString()).isEqualTo("GET");
		assertThat(event.get("httpStatus").asInt()).isEqualTo(200);
		assertThat(event.get("success").asBoolean()).isTrue();
		assertThat(event.get("latencyMs").asLong()).isGreaterThanOrEqualTo(0L);
		assertThat(event.get("requestId").asString()).isEqualTo(requestId);
		assertThat(event.get("errorType").isNull()).isTrue();
		assertThat(event.get("errorMessage").isNull()).isTrue();
	}

	/**
	 * Record에는 실제 URI, Query String, Request 내용이 들어가면 안 된다. endpoint에 Route
	 * Template만 실리므로 상품 ID조차 남지 않는다.
	 */
	@Test
	void neverPublishesRequestContentOrIdentifiers() throws Exception {
		this.mockMvc.perform(get(PRODUCT_ROUTE_TEMPLATE, SEEDED_PRODUCT_ID)).andExpect(status().isOk());

		ConsumerRecord<String, String> published = nextRecord();

		assertThat(published.value()).doesNotContain("/api/products/" + SEEDED_PRODUCT_ID);
		assertThat(published.key()).doesNotContain(String.valueOf(SEEDED_PRODUCT_ID));
		String lowerCased = published.value().toLowerCase(Locale.ROOT);
		for (String forbidden : FORBIDDEN_CONTENT) {
			assertThat(lowerCased).doesNotContain(forbidden);
		}
	}

	/**
	 * 같은 endpoint의 Event가 한 partition에 모여야 PulseOps가 endpoint 단위로 순서 있게 집계할
	 * 수 있다. partition 번호 자체는 Broker가 정하므로 값을 단정하지 않고 일치만 확인한다.
	 */
	@Test
	void routesEventsWithTheSameKeyToTheSamePartition() throws Exception {
		int requestCount = 3;
		for (int i = 0; i < requestCount; i++) {
			this.mockMvc.perform(get(PRODUCT_ROUTE_TEMPLATE, SEEDED_PRODUCT_ID)).andExpect(status().isOk());
		}

		int firstPartition = nextRecord().partition();
		for (int i = 1; i < requestCount; i++) {
			ConsumerRecord<String, String> published = nextRecord();
			assertThat(published.key()).isEqualTo(PRODUCT_EVENT_KEY);
			assertThat(published.partition()).isEqualTo(firstPartition);
		}
	}

	// --- 헬퍼 ---------------------------------------------------------------

	/**
	 * Kafka 전송은 비동기이므로 Record가 도착할 때까지 기다린다. 한 번의 poll이 여러 건을
	 * 돌려줄 수 있으므로 남는 Record는 버리지 않고 다음 호출을 위해 보관한다.
	 */
	private ConsumerRecord<String, String> nextRecord() {
		drainInto(this.received, RECORD_TIMEOUT);
		if (this.received.isEmpty()) {
			throw new AssertionError("Telemetry Record가 " + RECORD_TIMEOUT + " 안에 도착하지 않았다.");
		}
		return this.received.poll();
	}

	private void drainInto(Deque<ConsumerRecord<String, String>> target, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (target.isEmpty() && System.nanoTime() < deadline) {
			this.consumer.poll(Duration.ofMillis(200)).forEach(target::add);
		}
	}

	private Map<String, Object> consumerConfig() {
		Map<String, Object> config = new HashMap<>();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, this.kafkaContainer.getBootstrapServers());
		config.put(ConsumerConfig.GROUP_ID_CONFIG, "telemetry-producer-test-" + UUID.randomUUID());
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		return config;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class KafkaTestcontainersConfiguration {

		@Bean
		@ServiceConnection
		KafkaContainer kafkaContainer() {
			return new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));
		}

	}

}

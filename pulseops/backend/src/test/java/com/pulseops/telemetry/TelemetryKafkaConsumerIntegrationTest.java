package com.pulseops.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.pulseops.MySqlTestcontainersConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Demo Shop이 실제로 발행하는 형태의 Record가 실제 Kafka Broker를 거쳐 PulseOps
 * {@code @KafkaListener}까지 도달해 어떻게 처리되는지 검증한다.
 * <p>
 * Listener method를 직접 호출하지 않는다. Record는 raw Producer가 Broker에 넣고 Spring Kafka
 * Listener Container가 꺼내 전달한다. 실제 직렬화, partition, consumer group, offset 경계를 지나야
 * Phase 2-4 Producer 계약과 Phase 2-5 Consumer 계약이 같은 것을 말하고 있는지 확인할 수 있다.
 * <p>
 * Listener는 아직 Redis나 DB에 아무것도 쓰지 않고 log만 남기므로, 처리 결과는 log로 관측한다.
 * 정상 Event는 DEBUG, 건너뛴 Event는 WARN이라는 것 자체가 현재 계약이다. Listener Bean을 Mock으로
 * 바꾸면 검증하려는 Container 경로가 사라지므로 Bean은 그대로 두고 logger에만 Appender를 붙인다.
 * <p>
 * 각 검증은 자신이 보낸 Record의 partition과 offset으로 해당 log를 찾는다. 모든 테스트가 하나의
 * Topic과 하나의 Consumer를 공유하지만 실행 순서에 의존하지 않는 이유이다.
 * <p>
 * MySQL은 Context에 Datasource가 있어 함께 띄운다. {@link MySqlTestcontainersConfiguration} 참고.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelemetryKafkaConsumerIntegrationTest {

	private static final String KAFKA_IMAGE = "apache/kafka:4.0.0";

	/** Phase 2에서 확정한 값이다. Broker가 auto creation으로 만드는 기본값과 다르다. */
	private static final int EXPECTED_PARTITIONS = 3;

	private static final int EXPECTED_REPLICAS = 1;

	/** Phase 2에서 확정한 Consumer group이다. 테스트 편의로 다른 이름을 쓰지 않는다. */
	private static final String CONSUMER_GROUP = "pulseops-metric-consumer-v1";

	private static final String FIXTURE_PATH = "telemetry/telemetry-event-v1.json";

	/** Demo Shop Producer가 실제로 쓰는 Key 형식이다. */
	private static final String PRODUCT_EVENT_KEY = "demo-shop:/api/products/{productId}";

	private static final String PAYMENT_EVENT_KEY = "demo-shop:/api/payments";

	private static final String SKIP_LOG_MARKER = "Telemetry Event를 건너뛴다";

	private static final Duration LOG_TIMEOUT = Duration.ofSeconds(60);

	private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private KafkaContainer kafkaContainer;

	@Autowired
	private KafkaProperties kafkaProperties;

	private KafkaProducer<String, String> producer;

	private final TelemetryLogRecorder logRecorder = new TelemetryLogRecorder();

	@BeforeAll
	void startRecordingTelemetryLog() {
		this.producer = new KafkaProducer<>(producerConfig());
		this.logRecorder.attach();
	}

	@AfterAll
	void releaseTestResources() {
		this.logRecorder.detach();
		this.producer.close();
	}

	// --- Topic --------------------------------------------------------------

	/**
	 * Consumer만 있는 환경에서 Broker의 auto creation에 맡기면 partition이 1개인 Topic이 생긴다.
	 * 그러면 endpoint별 집계 단위가 Phase 2에서 정한 것과 달라지므로, 이 테스트는 3 partition Topic
	 * 위에서만 의미가 있다. Topic은 Consumer가 아니라 테스트 fixture가 미리 만든다.
	 */
	@Test
	void runsOnTheThreePartitionTelemetryTopic() throws Exception {
		try (AdminClient admin = AdminClient.create(
				Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, this.kafkaContainer.getBootstrapServers()))) {
			TopicDescription topic = admin.describeTopics(List.of(TelemetryEventListener.TOPIC))
				.allTopicNames()
				.get()
				.get(TelemetryEventListener.TOPIC);

			assertThat(topic.partitions()).hasSize(EXPECTED_PARTITIONS);
			assertThat(topic.partitions()).allSatisfy(
					(partition) -> assertThat(partition.replicas()).hasSize(EXPECTED_REPLICAS));
		}
	}

	/**
	 * Consumer group과 offset 시작 지점은 Application 설정에서 오는 값이다. 테스트가 자체 group을
	 * 만들어 검증을 우회하지 않았다는 것과, 실제 설정 자체가 Phase 2에서 정한 값이라는 것을 함께
	 * 확인한다.
	 */
	@Test
	void runsUnderTheConfiguredConsumerGroup() {
		assertThat(this.kafkaProperties.getConsumer().getGroupId()).isEqualTo(CONSUMER_GROUP);
		assertThat(this.kafkaProperties.getConsumer().getAutoOffsetReset()).isEqualTo("earliest");
	}

	// --- 정상 Event ---------------------------------------------------------

	/**
	 * canonical fixture를 한 글자도 바꾸지 않고 그대로 보낸다. Producer가 확정한 JSON Type이 Kafka를
	 * 지나 Consumer의 Jackson 3 ObjectMapper에서 같은 값으로 읽히는지 확인한다.
	 */
	@Test
	void consumesTheCanonicalFixtureStraightOffTheWire() throws Exception {
		String payload = readCanonicalFixture();
		JsonNode fixture = this.objectMapper.readTree(payload);
		assertThat(fixture.get("schemaVersion").isNumber()).isTrue();
		assertThat(fixture.get("httpStatus").isNumber()).isTrue();
		assertThat(fixture.get("latencyMs").isNumber()).isTrue();
		assertThat(fixture.get("success").isBoolean()).isTrue();
		assertThat(fixture.get("occurredAt").isString()).isTrue();

		ILoggingEvent log = publishAndAwaitLog(PAYMENT_EVENT_KEY, payload);

		assertThat(log.getLevel()).isEqualTo(Level.DEBUG);
		assertThat(log.getFormattedMessage()).contains("eventId=3f1c8a27-6b90-4d1e-9c44-72b0d5e19a2b")
			.contains("service=demo-shop")
			.contains("endpoint=/api/payments")
			.contains("httpStatus=409")
			.contains("latencyMs=37");
	}

	/**
	 * 409 Business Error는 Consumer 입장에서 정상 Telemetry Event다. Consumer가 이를 실패로 다루거나
	 * Incident로 해석하면 안 된다.
	 */
	@Test
	void consumesBusinessErrorEventAsAValidEvent() throws Exception {
		String eventId = "0e5c6b1a-1111-4aaa-9bbb-0123456789ab";
		String payload = canonicalPayloadWith((event) -> event.put("eventId", eventId));

		ILoggingEvent log = publishAndAwaitLog(PAYMENT_EVENT_KEY, payload);

		assertThat(log.getLevel()).isEqualTo(Level.DEBUG);
		assertThat(log.getFormattedMessage()).contains("eventId=" + eventId).contains("httpStatus=409");
	}

	/** 정상 200 Event는 errorType과 errorMessage가 null이다. 필드 자체는 13개 그대로 존재한다. */
	@Test
	void consumesSuccessEventWithNullErrorFields() throws Exception {
		String eventId = "0e5c6b1a-2222-4aaa-9bbb-0123456789ab";
		String payload = canonicalPayloadWith((event) -> {
			event.put("eventId", eventId);
			event.put("endpoint", "/api/products/{productId}");
			event.put("method", "GET");
			event.put("httpStatus", 200);
			event.put("success", true);
			event.putNull("errorType");
			event.putNull("errorMessage");
		});
		assertThat(this.objectMapper.readTree(payload).size()).isEqualTo(13);

		ILoggingEvent log = publishAndAwaitLog(PRODUCT_EVENT_KEY, payload);

		assertThat(log.getLevel()).isEqualTo(Level.DEBUG);
		assertThat(log.getFormattedMessage()).contains("eventId=" + eventId).contains("httpStatus=200");
	}

	/**
	 * Producer가 나중에 optional 필드를 더해도 v1 Consumer가 멈추면 안 된다. 단위 테스트에서 확인한
	 * forward compatibility가 실제 Kafka 경로에서도 유지되는지 본다.
	 */
	@Test
	void consumesEventCarryingAnUnknownFieldFromAFutureProducer() throws Exception {
		String eventId = "0e5c6b1a-3333-4aaa-9bbb-0123456789ab";
		String payload = canonicalPayloadWith((event) -> {
			event.put("eventId", eventId);
			event.put("futureOptionalField", "future-value");
		});

		ILoggingEvent log = publishAndAwaitLog(PAYMENT_EVENT_KEY, payload);

		assertThat(log.getLevel()).isEqualTo(Level.DEBUG);
		assertThat(log.getFormattedMessage()).contains("eventId=" + eventId);
	}

	// --- Invalid Event ------------------------------------------------------

	@Test
	void skipsMalformedJson() throws Exception {
		ILoggingEvent log = publishAndAwaitLog(PAYMENT_EVENT_KEY, "{not-json");

		assertThat(log.getLevel()).isEqualTo(Level.WARN);
		assertThat(log.getFormattedMessage()).contains(SKIP_LOG_MARKER).contains("JSON을 읽지 못했다");
	}

	@Test
	void skipsUnsupportedSchemaVersion() throws Exception {
		String payload = canonicalPayloadWith((event) -> event.put("schemaVersion", 2));

		ILoggingEvent log = publishAndAwaitLog(PAYMENT_EVENT_KEY, payload);

		assertThat(log.getLevel()).isEqualTo(Level.WARN);
		assertThat(log.getFormattedMessage()).contains(SKIP_LOG_MARKER).contains("schemaVersion");
	}

	/**
	 * latencyMs와 success는 누락돼도 역직렬화 후 0과 false가 되어 그 자체로 유효한 값과 구분되지
	 * 않는다. 필드 존재 검증이 실제 Kafka 경로에서도 이 둘을 잡아내는지 확인한다. 나머지 11개
	 * 필드 조합은 Validator 단위 테스트가 담당한다.
	 */
	@Test
	void skipsPayloadMissingLatencyMs() throws Exception {
		String payload = canonicalPayloadWith((event) -> event.remove("latencyMs"));

		ILoggingEvent log = publishAndAwaitLog(PAYMENT_EVENT_KEY, payload);

		assertThat(log.getLevel()).isEqualTo(Level.WARN);
		assertThat(log.getFormattedMessage()).contains("필수 필드 누락: latencyMs");
	}

	@Test
	void skipsPayloadMissingSuccess() throws Exception {
		String payload = canonicalPayloadWith((event) -> event.remove("success"));

		ILoggingEvent log = publishAndAwaitLog(PAYMENT_EVENT_KEY, payload);

		assertThat(log.getLevel()).isEqualTo(Level.WARN);
		assertThat(log.getFormattedMessage()).contains("필수 필드 누락: success");
	}

	@Test
	void skipsEventWithNegativeLatency() throws Exception {
		String payload = canonicalPayloadWith((event) -> event.put("latencyMs", -1));

		ILoggingEvent log = publishAndAwaitLog(PAYMENT_EVENT_KEY, payload);

		assertThat(log.getLevel()).isEqualTo(Level.WARN);
		assertThat(log.getFormattedMessage()).contains("latencyMs");
	}

	/** success는 v1에서 httpStatus < 400과 같은 뜻이다. 어긋난 Event는 Producer 계약 오류다. */
	@Test
	void skipsEventWhereSuccessContradictsHttpStatus() throws Exception {
		String payload = canonicalPayloadWith((event) -> {
			event.put("httpStatus", 200);
			event.put("success", false);
		});

		ILoggingEvent log = publishAndAwaitLog(PRODUCT_EVENT_KEY, payload);

		assertThat(log.getLevel()).isEqualTo(Level.WARN);
		assertThat(log.getFormattedMessage()).contains(SKIP_LOG_MARKER).contains("success");
	}

	// --- Offset 진행 --------------------------------------------------------

	/**
	 * 이번 Phase의 invalid event 정책이 실제로 작동하는지 보여 주는 핵심 검증이다. 잘못된 Record가
	 * 예외로 빠져나가면 같은 offset이 계속 재전달되어 뒤의 Record가 영원히 처리되지 않는다. 두
	 * Record를 같은 Key로 보내 같은 partition의 연속된 offset에 놓고, 뒤의 정상 Event가 실제로
	 * 처리되는 것과 Consumer group의 commit이 그 지점을 지나가는 것을 함께 확인한다.
	 */
	@Test
	void keepsConsumingAfterAPoisonRecordOnTheSamePartition() throws Exception {
		String eventId = "0e5c6b1a-4444-4aaa-9bbb-0123456789ab";
		RecordMetadata poison = publish(PRODUCT_EVENT_KEY, "{not-json");
		RecordMetadata healthy = publish(PRODUCT_EVENT_KEY,
				canonicalPayloadWith((event) -> event.put("eventId", eventId)));

		assertThat(healthy.partition()).isEqualTo(poison.partition());
		assertThat(healthy.offset()).isEqualTo(poison.offset() + 1);

		assertThat(awaitLogFor(poison).getLevel()).isEqualTo(Level.WARN);
		ILoggingEvent healthyLog = awaitLogFor(healthy);
		assertThat(healthyLog.getLevel()).isEqualTo(Level.DEBUG);
		assertThat(healthyLog.getFormattedMessage()).contains("eventId=" + eventId);

		awaitCommittedOffsetPast(healthy);
	}

	/**
	 * 같은 Key는 같은 partition으로 모인다. Producer 쪽 partition 선택은 Phase 2-4에서 이미
	 * 검증했으므로 여기서는 Consumer가 그 순서를 빠짐없이 따라오는 것만 확인한다.
	 */
	@Test
	void consumesConsecutiveEventsOfTheSameKeyInOffsetOrder() throws Exception {
		String eventIdPrefix = "0e5c6b1a-5555-4aaa-9bbb-00000000000";
		RecordMetadata previous = null;
		for (int i = 0; i < 3; i++) {
			String eventId = eventIdPrefix + i;
			RecordMetadata metadata = publish(PAYMENT_EVENT_KEY,
					canonicalPayloadWith((event) -> event.put("eventId", eventId)));
			if (previous != null) {
				assertThat(metadata.partition()).isEqualTo(previous.partition());
				assertThat(metadata.offset()).isEqualTo(previous.offset() + 1);
			}
			previous = metadata;

			ILoggingEvent log = awaitLogFor(metadata);
			assertThat(log.getLevel()).isEqualTo(Level.DEBUG);
			assertThat(log.getFormattedMessage()).contains("eventId=" + eventId);
		}
	}

	// --- Security -----------------------------------------------------------

	/**
	 * Event에는 Client에게 나간 errorMessage가 들어 있고 Jackson 예외 message에는 문제가 된 값이
	 * 그대로 담긴다. 실제 Kafka 경로를 지난 뒤에도 이 둘이 log로 새지 않는지 확인한다.
	 */
	@Test
	void neverWritesPayloadContentIntoTheTelemetryLog() throws Exception {
		String payload = canonicalPayloadWith((event) -> event.put("schemaVersion", 2));

		ILoggingEvent log = publishAndAwaitLog(PAYMENT_EVENT_KEY, payload);

		assertThat(log.getLevel()).isEqualTo(Level.WARN);
		assertThat(log.getFormattedMessage()).doesNotContain("이미 결제가 완료된 주문입니다.")
			.doesNotContain("ORDER_ALREADY_PAID")
			.contains("payloadLength=" + payload.length());
		assertThat(this.logRecorder.formattedMessages()).noneMatch((message) -> message.contains(payload));
	}

	// --- Kafka 헬퍼 ---------------------------------------------------------

	private ILoggingEvent publishAndAwaitLog(String key, String payload) throws Exception {
		return awaitLogFor(publish(key, payload));
	}

	private RecordMetadata publish(String key, String payload) throws Exception {
		return this.producer.send(new ProducerRecord<>(TelemetryEventListener.TOPIC, key, payload))
			.get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
	}

	/**
	 * Listener는 비동기로 동작하므로 log가 남을 때까지 기다린다. 고정 대기 대신 Record 자신의
	 * partition과 offset이 찍힌 줄을 찾으므로, 여러 테스트가 같은 Topic을 공유해도 서로의 log를
	 * 잘못 집어 들지 않는다.
	 */
	private ILoggingEvent awaitLogFor(RecordMetadata metadata) {
		// 뒤의 공백까지 포함해 찾는다. 그렇지 않으면 offset=1이 offset=10의 log에도 걸린다.
		String coordinates = "partition=" + metadata.partition() + " offset=" + metadata.offset() + " ";
		return await().atMost(LOG_TIMEOUT)
			.pollInterval(Duration.ofMillis(100))
			.until(() -> this.logRecorder.findByCoordinates(coordinates), Optional::isPresent)
			.orElseThrow();
	}

	/**
	 * Listener가 정상 return하면 Spring Kafka Container가 offset을 commit한다. commit이 해당 Record를
	 * 지나간다는 것은 Consumer가 그 자리에 고착되지 않았다는 뜻이다. Manual Ack를 쓰지 않고 기본
	 * 동작을 그대로 확인한다.
	 */
	private void awaitCommittedOffsetPast(RecordMetadata metadata) {
		TopicPartition partition = new TopicPartition(TelemetryEventListener.TOPIC, metadata.partition());
		try (AdminClient admin = AdminClient.create(
				Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, this.kafkaContainer.getBootstrapServers()))) {
			await().atMost(LOG_TIMEOUT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
				OffsetAndMetadata committed = admin.listConsumerGroupOffsets(CONSUMER_GROUP)
					.partitionsToOffsetAndMetadata()
					.get()
					.get(partition);
				assertThat(committed).isNotNull();
				assertThat(committed.offset()).isGreaterThan(metadata.offset());
			});
		}
	}

	private Map<String, Object> producerConfig() {
		Map<String, Object> config = new HashMap<>();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, this.kafkaContainer.getBootstrapServers());
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		return config;
	}

	// --- Payload 헬퍼 -------------------------------------------------------

	/** canonical fixture에서 필요한 부분만 바꿔 payload를 만든다. 나머지는 계약 그대로 남는다. */
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

	// --- 테스트 인프라 ------------------------------------------------------

	/**
	 * Telemetry logger의 출력을 테스트에서 읽는다. Listener는 아직 log 외의 흔적을 남기지 않으므로
	 * 처리 결과를 확인할 방법이 이것뿐이다. 전역 logging 설정을 바꾸지 않고 이 logger에만 Appender를
	 * 붙였다 떼며, 원래 level도 되돌린다.
	 */
	private static final class TelemetryLogRecorder extends AppenderBase<ILoggingEvent> {

		private final Queue<ILoggingEvent> events = new ConcurrentLinkedQueue<>();

		private Level originalLevel;

		private Logger logger() {
			return (Logger) LoggerFactory.getLogger(TelemetryEventListener.class.getPackageName());
		}

		void attach() {
			Logger logger = logger();
			this.originalLevel = logger.getLevel();
			logger.setLevel(Level.DEBUG);
			start();
			logger.addAppender(this);
		}

		void detach() {
			Logger logger = logger();
			logger.detachAppender(this);
			logger.setLevel(this.originalLevel);
			stop();
		}

		@Override
		protected void append(ILoggingEvent event) {
			this.events.add(event);
		}

		Optional<ILoggingEvent> findByCoordinates(String coordinates) {
			return this.events.stream().filter((event) -> event.getFormattedMessage().contains(coordinates)).findFirst();
		}

		Collection<String> formattedMessages() {
			return this.events.stream().map(ILoggingEvent::getFormattedMessage).toList();
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class KafkaTestcontainersConfiguration {

		@Bean
		@ServiceConnection
		KafkaContainer kafkaContainer() {
			return new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));
		}

		/**
		 * 테스트 fixture이다. PulseOps Consumer는 Topic owner가 아니므로 Production에는 NewTopic이
		 * 없고, Broker의 auto creation에 맡기면 partition이 1개인 Topic이 생긴다. 실제 운영에서
		 * Demo Shop이 만들어 주는 것과 같은 Topic 위에서 검증하기 위해 여기서 미리 만든다.
		 * KafkaAdmin은 Listener Container가 기동하기 전에 Topic을 준비한다.
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

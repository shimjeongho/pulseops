package com.pulseops.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.telemetry.TelemetryEventListener;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer Lag API가 무엇을 내보내고 무엇을 내보내지 않는지를 고정한다.
 * <p>
 * Broker를 띄우지 않는다. 여기서 확인할 것은 Kafka가 무엇을 알려 주는지가 아니라 그 값이 어떤 계약으로
 * 나가는지와, 실패했을 때 무엇을 감추는지이다. Broker 위에서 offset이 실제로 어떻게 읽히는지는
 * {@link KafkaAdminConsumerLagClientIntegrationTest}가 따로 본다.
 * <p>
 * MySQL은 Context에 Datasource가 있어 함께 띄운다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import({ MySqlTestcontainersConfiguration.class, FakeKafkaConsumerLagClientConfiguration.class })
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class ConsumerLagApiIntegrationTest {

	private static final String PATH = "/api/kafka/consumer-lag";

	/** Phase 2에서 확정한 group이다. 테스트 편의로 다른 이름을 쓰지 않는다. */
	private static final String CONSUMER_GROUP = "pulseops-metric-consumer-v1";

	private static final List<String> TOP_LEVEL_FIELDS = List.of("generatedAt", "topic", "consumerGroup",
			"partitionCount", "uninitializedPartitionCount", "totalLag", "partitions");

	private static final List<String> PARTITION_FIELDS = List.of("partition", "logEndOffset", "committedOffset",
			"lag", "state");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private FakeKafkaConsumerLagClient lagClient;

	@AfterEach
	void resetClient() {
		this.lagClient.reset();
	}

	@Test
	void answersWithTheAgreedSnapshotShape() throws Exception {
		this.lagClient.answerWith(List.of(new PartitionOffsets(0, 120L, 100L), new PartitionOffsets(1, 40L, 40L),
				new PartitionOffsets(2, 900L, null)));

		JsonNode body = getSnapshot();

		assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(TOP_LEVEL_FIELDS);
		assertThat(body.get("topic").stringValue()).isEqualTo(TelemetryEventListener.TOPIC);
		assertThat(body.get("consumerGroup").stringValue()).isEqualTo(CONSUMER_GROUP);
		assertThat(body.get("partitionCount").intValue()).isEqualTo(3);
		assertThat(body.get("uninitializedPartitionCount").intValue()).isEqualTo(1);
		assertThat(body.get("totalLag").longValue()).isEqualTo(20L);
		assertThat(body.get("partitions").get(0).propertyNames())
			.containsExactlyInAnyOrderElementsOf(PARTITION_FIELDS);
	}

	/** 아직 읽지 않은 partition은 lag이 없는 것으로 나간다. 0으로 나가면 화면이 그것을 회복으로 그린다. */
	@Test
	void sendsNullRatherThanZeroForAPartitionWithoutACommittedOffset() throws Exception {
		this.lagClient.answerWith(List.of(new PartitionOffsets(0, 900L, null)));

		JsonNode partition = getSnapshot().get("partitions").get(0);

		assertThat(partition.get("lag").isNull()).isTrue();
		assertThat(partition.get("committedOffset").isNull()).isTrue();
		assertThat(partition.get("state").stringValue()).isEqualTo("UNINITIALIZED");
	}

	/**
	 * 무엇을 볼지는 서버가 정한다. 요청이 정하게 하면 이 endpoint가 우리 연결로 Cluster 안을 아무 곳이나
	 * 들여다보는 창구가 된다.
	 */
	@Test
	void ignoresAnyTopicOrGroupTheCallerTriesToSupply() throws Exception {
		this.mockMvc.perform(get(PATH).param("topic", "__consumer_offsets").param("group", "someone-else"))
			.andExpect(status().isOk());

		assertThat(this.lagClient.requestedTopics()).containsExactly(TelemetryEventListener.TOPIC);
		assertThat(this.lagClient.requestedConsumerGroups()).containsExactly(CONSUMER_GROUP);
	}

	/** 조회 전용 경로이다. 상태를 바꾸는 method는 아예 없다. */
	@Test
	void refusesAnythingButReading() throws Exception {
		this.mockMvc.perform(post(PATH)).andExpect(status().isMethodNotAllowed());

		assertThat(this.lagClient.requestedTopics()).isEmpty();
	}

	/**
	 * Broker에 닿지 못한 것을 빈 목록과 {@code totalLag=0}으로 바꾸지 않는다. 그렇게 하면 Broker 장애가
	 * 화면에서 가장 건강한 상태로 보인다.
	 */
	@Test
	void answersWithAFailureRatherThanAnEmptySnapshotWhenKafkaCannotBeReached() throws Exception {
		this.lagClient.failWith(new ConsumerLagUnavailableException("committed-offsets",
				new IllegalStateException("Timed out waiting for node assignment. bootstrap=broker-9.internal:9092")));

		JsonNode body = this.objectMapper.readTree(this.mockMvc.perform(get(PATH))
			.andExpect(status().isServiceUnavailable())
			.andReturn()
			.getResponse()
			.getContentAsString());

		assertThat(body.get("code").stringValue()).isEqualTo("KAFKA_LAG_UNAVAILABLE");
		assertThat(body.get("totalLag")).isNull();
	}

	/**
	 * Kafka 예외의 message에는 Broker 주소와 내부 요청 이름이 담겨 온다. 응답에 그 어떤 조각도 나가지
	 * 않아야 한다.
	 */
	@Test
	void keepsKafkaInternalsOutOfTheFailureResponse() throws Exception {
		this.lagClient.failWith(new ConsumerLagUnavailableException("log-end-offsets",
				new IllegalStateException("broker-9.internal:9092 listOffsets UNKNOWN_TOPIC_OR_PARTITION")));

		String body = this.mockMvc.perform(get(PATH))
			.andExpect(status().isServiceUnavailable())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).doesNotContain("broker-9.internal", "listOffsets", "UNKNOWN_TOPIC_OR_PARTITION",
				"log-end-offsets", "IllegalStateException");
	}

	private JsonNode getSnapshot() throws Exception {
		return this.objectMapper.readTree(this.mockMvc.perform(get(PATH))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

}

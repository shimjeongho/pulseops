package com.pulseops.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 Broker 위에서 offset 두 가지가 어떤 값으로 읽히는지를 고정한다.
 * <p>
 * Kafka의 offset 계약을 흉내 내지 않는다. "committed offset은 다음에 읽을 offset"이라는 문장은 문서에
 * 적혀 있지만, 그 문장을 우리가 제대로 읽었는지는 진짜 Consumer가 진짜로 commit한 값을 우리 Client가
 * 다시 읽어 봐야 확인된다. 흉내 낸 Broker는 우리가 믿고 있는 것을 그대로 되돌려 줄 뿐이다.
 * <p>
 * Spring Context를 띄우지 않는다. 여기서 확인할 것은 Kafka Admin API를 제대로 쓰고 있는지 하나뿐이고,
 * 그것에 Datasource도 Listener도 필요하지 않다.
 * <p>
 * 각 검증은 자기가 쓸 partition과 group을 스스로 정한다. 하나의 Broker를 함께 쓰지만 실행 순서에
 * 의존하지 않는 이유이다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaAdminConsumerLagClientIntegrationTest {

	private static final String KAFKA_IMAGE = "apache/kafka:4.0.0";

	/** Demo Shop이 만드는 Topic과 같은 모양이다. */
	private static final String TOPIC = "demo-shop.telemetry.api.v1";

	private static final int PARTITIONS = 3;

	private static final short REPLICAS = 1;

	/** 같은 group이 함께 구독할 수 있는 다른 Topic이다. 이것의 offset이 섞여 들어오면 안 된다. */
	private static final String OTHER_TOPIC = "demo-shop.telemetry.api.v1.dlt";

	/** partition별 Record 수이다. partition 2는 비워 두어 끝이 0인 경우도 함께 본다. */
	private static final int PARTITION_0_RECORDS = 5;

	private static final int PARTITION_1_RECORDS = 3;

	private static final int OTHER_TOPIC_RECORDS = 7;

	/**
	 * Broker에 닿지 못할 때 얼마 만에 포기하는지를 보는 한도이다. Client가 정한 3초보다 넉넉하지만,
	 * 스스로 여러 번 다시 시도하는 고리가 있다면 넘길 수밖에 없는 값으로 잡는다.
	 */
	private static final Duration UNREACHABLE_BROKER_BUDGET = Duration.ofSeconds(15);

	private static final Duration POLL_TIMEOUT = Duration.ofSeconds(10);

	@Container
	private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));

	private static Admin admin;

	private static KafkaAdminConsumerLagClient client;

	@BeforeAll
	static void prepareBroker() throws Exception {
		admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
		admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, REPLICAS), new NewTopic(OTHER_TOPIC, 1, REPLICAS)))
			.all()
			.get();
		client = new KafkaAdminConsumerLagClient(admin);
		publish(TOPIC, 0, PARTITION_0_RECORDS);
		publish(TOPIC, 1, PARTITION_1_RECORDS);
		publish(OTHER_TOPIC, 0, OTHER_TOPIC_RECORDS);
	}

	@AfterAll
	static void closeAdmin() {
		admin.close();
	}

	/**
	 * 아무도 읽지 않은 Topic에서도 끝은 알 수 있다. 그리고 그 상태의 committed offset은 0이 아니라 없는
	 * 것이며, Kafka가 그것을 null로 알려 준다는 계약을 여기서 고정한다.
	 */
	@Test
	void readsTheEndOfEveryPartitionAndReportsNoCommitForAGroupThatNeverRan() {
		List<PartitionOffsets> offsets = client.readOffsets(TOPIC, "group-that-never-ran-v1");

		assertThat(offsets).hasSize(PARTITIONS);
		assertThat(offsets).allSatisfy((partition) -> assertThat(partition.committedOffset()).isNull());
		assertThat(logEndOffsetOf(offsets, 0)).isEqualTo(PARTITION_0_RECORDS);
		assertThat(logEndOffsetOf(offsets, 1)).isEqualTo(PARTITION_1_RECORDS);
		assertThat(logEndOffsetOf(offsets, 2)).isZero();
	}

	/**
	 * 진짜 Consumer가 partition 0의 5건을 전부 읽고 commit한 뒤의 값을 읽는다. 남는 값이 "마지막으로 읽은
	 * offset 4"가 아니라 "다음에 읽을 offset 5"라는 것이 Kafka의 계약이며, 그래서 lag을 구할 때 1을
	 * 더하거나 빼지 않는다.
	 */
	@Test
	void readsTheOffsetARealConsumerCommittedAsTheNextOneToRead() {
		String consumerGroup = "reads-to-the-end-v1";
		consumeEverythingAndCommit(consumerGroup, new TopicPartition(TOPIC, 0), PARTITION_0_RECORDS);

		PartitionOffsets partition = partitionOf(client.readOffsets(TOPIC, consumerGroup), 0);

		assertThat(partition.committedOffset()).isEqualTo(PARTITION_0_RECORDS);
		assertThat(partition.logEndOffset()).isEqualTo(PARTITION_0_RECORDS);
	}

	/** 뒤처진 Consumer는 두 offset의 차이로 드러난다. */
	@Test
	void showsTheDistanceOfAGroupThatStoppedPartWay() {
		String consumerGroup = "stopped-part-way-v1";
		commitPosition(consumerGroup, new TopicPartition(TOPIC, 0), 2);

		PartitionOffsets partition = partitionOf(client.readOffsets(TOPIC, consumerGroup), 0);

		assertThat(partition.committedOffset()).isEqualTo(2L);
		assertThat(partition.logEndOffset()).isEqualTo(PARTITION_0_RECORDS);
	}

	/** 같은 group이 다른 Topic도 읽고 있어도 물어본 Topic의 partition만 돌아온다. */
	@Test
	void readsOnlyTheTopicItWasAskedAbout() {
		String consumerGroup = "reads-two-topics-v1";
		commitPosition(consumerGroup, new TopicPartition(TOPIC, 0), 1);
		commitPosition(consumerGroup, new TopicPartition(OTHER_TOPIC, 0), OTHER_TOPIC_RECORDS);

		List<PartitionOffsets> offsets = client.readOffsets(TOPIC, consumerGroup);

		assertThat(offsets).hasSize(PARTITIONS);
		assertThat(offsets).extracting(PartitionOffsets::partition).containsExactlyInAnyOrder(0, 1, 2);
	}

	/** 다른 group이 같은 partition을 어디까지 읽었든 우리 group의 진행과 섞이지 않는다. */
	@Test
	void readsOnlyTheGroupItWasAskedAbout() {
		TopicPartition topicPartition = new TopicPartition(TOPIC, 1);
		commitPosition("ours-v1", topicPartition, 1);
		commitPosition("theirs-v1", topicPartition, 3);

		PartitionOffsets partition = partitionOf(client.readOffsets(TOPIC, "ours-v1"), 1);

		assertThat(partition.committedOffset()).isEqualTo(1L);
	}

	/**
	 * 조회는 관측이다. 관측이 관측 대상을 바꾸면 그것으로 본 값은 더 이상 그 시스템의 상태가 아니다.
	 * Consumer의 진행을 앞당기거나 되돌리지 않는다는 것을 Broker에 남은 값으로 직접 확인한다.
	 */
	@Test
	void leavesTheGroupProgressExactlyWhereItWas() throws Exception {
		String consumerGroup = "must-not-move-v1";
		commitPosition(consumerGroup, new TopicPartition(TOPIC, 0), 2);
		commitPosition(consumerGroup, new TopicPartition(TOPIC, 1), 3);
		Map<TopicPartition, OffsetAndMetadata> before = committedOffsetsOf(consumerGroup);

		client.readOffsets(TOPIC, consumerGroup);
		client.readOffsets(TOPIC, consumerGroup);

		assertThat(committedOffsetsOf(consumerGroup)).isEqualTo(before);
	}

	/**
	 * 닿지 못하는 Broker에 무한정 매달리지 않는다. 정해진 시간 안에 실패로 답해야 화면이 다음 주기를
	 * 시작할 수 있다. 스스로 다시 시도하는 고리가 있다면 이 한도를 넘긴다.
	 */
	@Test
	void givesUpWithinItsTimeoutWhenTheBrokerCannotBeReached() {
		try (Admin unreachable = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1"))) {
			KafkaAdminConsumerLagClient unreachableClient = new KafkaAdminConsumerLagClient(unreachable);
			long startedAt = System.nanoTime();

			assertThatThrownBy(() -> unreachableClient.readOffsets(TOPIC, "any-group-v1"))
				.isInstanceOf(ConsumerLagUnavailableException.class)
				.hasFieldOrPropertyWithValue("reason", "topic-metadata");

			assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(UNREACHABLE_BROKER_BUDGET);
		}
	}

	/** 없는 Topic을 조용히 빈 결과로 답하지 않는다. 물어본 것이 없는 것과 밀린 것이 없는 것은 다르다. */
	@Test
	void refusesToAnswerForATopicThatDoesNotExist() {
		assertThatThrownBy(() -> client.readOffsets("no-such-topic", "any-group-v1"))
			.isInstanceOf(ConsumerLagUnavailableException.class)
			.hasFieldOrPropertyWithValue("reason", "topic-metadata");
	}

	private static Map<TopicPartition, OffsetAndMetadata> committedOffsetsOf(String consumerGroup) throws Exception {
		return admin.listConsumerGroupOffsets(Map.of(consumerGroup, new ListConsumerGroupOffsetsSpec()))
			.partitionsToOffsetAndMetadata()
			.get();
	}

	private static long logEndOffsetOf(List<PartitionOffsets> offsets, int partition) {
		return partitionOf(offsets, partition).logEndOffset();
	}

	private static PartitionOffsets partitionOf(List<PartitionOffsets> offsets, int partition) {
		return offsets.stream()
			.filter((candidate) -> candidate.partition() == partition)
			.findFirst()
			.orElseThrow(() -> new AssertionError("partition " + partition + "이(가) 결과에 없다"));
	}

	private static void publish(String topic, int partition, int count) throws Exception {
		Properties config = new Properties();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
			for (int index = 0; index < count; index++) {
				producer.send(new ProducerRecord<>(topic, partition, null, "{}")).get();
			}
		}
	}

	/**
	 * 진짜 Consumer로 끝까지 읽고 commit한다. Admin으로 offset을 직접 써 넣으면 우리가 믿는 값을 우리가
	 * 다시 읽는 셈이 되어, Consumer가 실제로 남기는 값과 같은지는 확인되지 않는다.
	 */
	private static void consumeEverythingAndCommit(String consumerGroup, TopicPartition topicPartition,
			int recordCount) {
		try (KafkaConsumer<String, String> consumer = consumer(consumerGroup)) {
			consumer.assign(List.of(topicPartition));
			consumer.seekToBeginning(List.of(topicPartition));
			int consumed = 0;
			while (consumed < recordCount) {
				consumed += consumer.poll(POLL_TIMEOUT).count();
			}
			consumer.commitSync();
		}
	}

	/**
	 * 특정 지점까지 읽은 Consumer를 만든다. Record를 실제로 꺼내면 한 번의 poll이 몇 건을 돌려줄지 정할
	 * 수 없어 commit되는 값이 흔들리는데, 여기서 필요한 것은 정확히 정해진 위치이다.
	 */
	private static void commitPosition(String consumerGroup, TopicPartition topicPartition, long offset) {
		try (KafkaConsumer<String, String> consumer = consumer(consumerGroup)) {
			consumer.assign(List.of(topicPartition));
			consumer.seek(topicPartition, offset);
			consumer.commitSync();
		}
	}

	private static KafkaConsumer<String, String> consumer(String consumerGroup) {
		Properties config = new Properties();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		config.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
		config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		return new KafkaConsumer<>(config);
	}

}

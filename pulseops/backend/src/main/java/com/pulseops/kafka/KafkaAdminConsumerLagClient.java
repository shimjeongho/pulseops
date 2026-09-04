package com.pulseops.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;

/**
 * Kafka Admin API로 offset을 읽는다.
 * <p>
 * 세 번 물어본다. Topic에 어떤 partition이 있는지, group이 그 partition들에 무엇을 commit해 두었는지,
 * 그리고 각 partition의 끝이 어디인지이다. 셋 다 partition 목록 전체를 한 요청에 담으므로, partition이
 * 늘어도 Broker와 주고받는 횟수는 늘지 않는다.
 * <p>
 * {@link Admin}은 Application이 하나만 갖고 재사용한다. 요청마다 만들면 요청마다 Broker 연결과 metadata
 * 조회를 처음부터 다시 하게 되고, 5초마다 읽는 화면에서 그 비용이 그대로 반복된다.
 * <p>
 * 다시 시도하는 고리를 만들지 않는다. Admin Client 자체가 정해진 시간 안에서 재시도하며, 그 위에 우리
 * 고리를 하나 더 얹으면 실패한 조회가 다음 조회 주기까지 이어져 요청이 겹친다. 한 번 실패하면 실패로
 * 답하고, 다시 보는 것은 다음 주기의 몫이다.
 */
public class KafkaAdminConsumerLagClient implements KafkaConsumerLagClient {

	/**
	 * Broker에게 한 번 물어볼 때 기다리는 시간이다.
	 * <p>
	 * 짧게 잡는다. 이 조회는 사람이 보고 있는 화면이 5초마다 부르는 요청이라, 한 번의 조회가 그보다 오래
	 * 걸리면 답이 도착할 때쯤 이미 다음 질문이 필요한 값이 된다. 건강한 Broker는 이 조회에 수 밀리초로
	 * 답하므로, 3초가 걸린다는 것은 느린 것이 아니라 닿지 못하고 있다는 뜻이다.
	 */
	private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(3);

	/**
	 * Admin Client는 위 시간이 지나면 결과를 실패로 완성해 주기로 되어 있다. 그래도 결과를 기다리는 쪽에
	 * 따로 한도를 둔다. 그 약속이 지켜지지 않는 상황(예: Client 내부 Thread가 죽은 경우)에서 요청을 받은
	 * Thread가 영원히 묶이는 것을 막기 위한 것이며, 평상시에는 이 한도에 닿지 않는다.
	 */
	private static final Duration RESULT_TIMEOUT = OPERATION_TIMEOUT.plusSeconds(2);

	private final Admin admin;

	public KafkaAdminConsumerLagClient(Admin admin) {
		this.admin = admin;
	}

	/**
	 * committed offset을 log end offset보다 먼저 읽는다. 두 값은 서로 다른 요청으로 오므로 그 사이에
	 * Consumer가 진행할 수 있는데, 이 순서면 committed 쪽이 조금 낡아 lag이 실제보다 크게 보인다. 반대로
	 * 읽으면 실제보다 작게 보인다. 관측값이 틀릴 수밖에 없다면 밀림을 덜 보고하는 쪽보다 더 보고하는
	 * 쪽으로 틀리는 편이 낫다.
	 */
	@Override
	public List<PartitionOffsets> readOffsets(String topic, String consumerGroup) {
		List<TopicPartition> topicPartitions = describePartitions(topic);
		Map<TopicPartition, OffsetAndMetadata> committedOffsets = committedOffsets(consumerGroup, topicPartitions);
		Map<TopicPartition, ListOffsetsResultInfo> logEndOffsets = logEndOffsets(topicPartitions);
		return topicPartitions.stream()
			.map((topicPartition) -> toPartitionOffsets(topicPartition, logEndOffsets, committedOffsets))
			.toList();
	}

	/**
	 * partition 목록은 Broker에게 묻는다. 설정에 적힌 수를 믿지 않는다. Topic이 실제로 몇 개의 partition을
	 * 갖고 있는지가 lag을 합칠 범위를 정하는데, 그 수는 운영 중에 늘어날 수 있고 우리가 만든 Topic도
	 * 아니다.
	 */
	private List<TopicPartition> describePartitions(String topic) {
		DescribeTopicsOptions options = new DescribeTopicsOptions().timeoutMs(operationTimeoutMillis());
		Map<String, TopicDescription> descriptions = await(this.admin.describeTopics(List.of(topic), options)
			.allTopicNames(), "topic-metadata");
		return descriptions.get(topic)
			.partitions()
			.stream()
			.map((partition) -> new TopicPartition(topic, partition.partition()))
			.toList();
	}

	/**
	 * 볼 partition을 spec에 적어 이 Topic의 것만 받는다. group 전체를 받아 와서 걸러 내면 이 group이
	 * 구독하는 다른 Topic의 offset까지 Application 안으로 들어온다.
	 */
	private Map<TopicPartition, OffsetAndMetadata> committedOffsets(String consumerGroup,
			List<TopicPartition> topicPartitions) {
		ListConsumerGroupOffsetsSpec spec = new ListConsumerGroupOffsetsSpec().topicPartitions(topicPartitions);
		ListConsumerGroupOffsetsOptions options = new ListConsumerGroupOffsetsOptions()
			.timeoutMs(operationTimeoutMillis());
		return await(this.admin.listConsumerGroupOffsets(Map.of(consumerGroup, spec), options)
			.partitionsToOffsetAndMetadata(), "committed-offsets");
	}

	/**
	 * {@code OffsetSpec.latest()}가 log end offset이다. {@code maxTimestamp()}는 Record의 timestamp를
	 * 기준으로 하며 Producer가 그 값을 정할 수 있어 log의 끝과 다를 수 있다.
	 */
	private Map<TopicPartition, ListOffsetsResultInfo> logEndOffsets(List<TopicPartition> topicPartitions) {
		Map<TopicPartition, OffsetSpec> request = topicPartitions.stream()
			.collect(Collectors.toMap(Function.identity(), (topicPartition) -> OffsetSpec.latest()));
		ListOffsetsOptions options = new ListOffsetsOptions().timeoutMs(operationTimeoutMillis());
		return await(this.admin.listOffsets(request, options).all(), "log-end-offsets");
	}

	/**
	 * commit 기록이 없는 partition은 값이 null로 온다. Kafka가 그렇게 하기로 정해 둔 것이며, 그 null을
	 * 그대로 위로 올려 보낸다. 여기서 0으로 채우면 아직 읽지 않은 partition이 다 따라잡은 partition이 된다.
	 */
	private static PartitionOffsets toPartitionOffsets(TopicPartition topicPartition,
			Map<TopicPartition, ListOffsetsResultInfo> logEndOffsets,
			Map<TopicPartition, OffsetAndMetadata> committedOffsets) {
		ListOffsetsResultInfo logEndOffset = logEndOffsets.get(topicPartition);
		if (logEndOffset == null) {
			throw new ConsumerLagUnavailableException("log-end-offset-missing", null);
		}
		OffsetAndMetadata committedOffset = committedOffsets.get(topicPartition);
		return new PartitionOffsets(topicPartition.partition(), logEndOffset.offset(),
				(committedOffset != null) ? committedOffset.offset() : null);
	}

	/**
	 * Kafka가 던지는 것을 그대로 위로 보내지 않는다. 그 예외들의 message에는 Broker 주소와 내부 요청
	 * 이름이 담겨 있고, Web 계층은 어느 저장소가 왜 아픈지 알지 못한 채 그것을 응답으로 옮기게 된다.
	 * <p>
	 * 중단 신호는 삼키지 않고 Thread에 되돌린 뒤 실패로 바꾼다. 삼키면 요청을 처리하던 Thread가 자기가
	 * 중단됐다는 사실을 잊는다.
	 */
	private static <T> T await(KafkaFuture<T> future, String reason) {
		try {
			return future.get(RESULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ConsumerLagUnavailableException(reason, ex);
		}
		catch (ExecutionException | TimeoutException ex) {
			throw new ConsumerLagUnavailableException(reason, ex);
		}
	}

	private static int operationTimeoutMillis() {
		return (int) OPERATION_TIMEOUT.toMillis();
	}

}

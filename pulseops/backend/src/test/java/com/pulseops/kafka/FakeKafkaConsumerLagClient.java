package com.pulseops.kafka;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * 진짜 Kafka 대신 세워 두는 Client이다.
 * <p>
 * API가 offset을 무엇으로 옮겨 내보내는지와 실패를 어떻게 답하는지를 확인하는 테스트가 Broker를 띄울
 * 필요가 없게 한다. Broker 위에서 offset이 실제로 어떻게 읽히는지는
 * {@link KafkaAdminConsumerLagClientIntegrationTest}가 따로 본다.
 * <p>
 * 무엇을 물어봤는지 기록해 둔다. "서버가 정한 Topic과 group만 본다"는 약속은 응답만 봐서는 확인되지
 * 않고, 요청에 담겨 온 인자로만 확인할 수 있다.
 * <p>
 * 이 Class는 테스트 전용이며 Production 경로에는 없다.
 */
public class FakeKafkaConsumerLagClient implements KafkaConsumerLagClient {

	private final List<String> requestedTopics = new CopyOnWriteArrayList<>();

	private final List<String> requestedConsumerGroups = new CopyOnWriteArrayList<>();

	private volatile Supplier<List<PartitionOffsets>> behavior = FakeKafkaConsumerLagClient::defaultOffsets;

	@Override
	public List<PartitionOffsets> readOffsets(String topic, String consumerGroup) {
		this.requestedTopics.add(topic);
		this.requestedConsumerGroups.add(consumerGroup);
		return this.behavior.get();
	}

	public void reset() {
		this.requestedTopics.clear();
		this.requestedConsumerGroups.clear();
		this.behavior = FakeKafkaConsumerLagClient::defaultOffsets;
	}

	public void answerWith(List<PartitionOffsets> offsets) {
		this.behavior = () -> offsets;
	}

	public void failWith(RuntimeException exception) {
		this.behavior = () -> {
			throw exception;
		};
	}

	public List<String> requestedTopics() {
		return List.copyOf(this.requestedTopics);
	}

	public List<String> requestedConsumerGroups() {
		return List.copyOf(this.requestedConsumerGroups);
	}

	/** Demo Shop이 만드는 3 partition Topic을 다 따라잡은 상태이다. */
	private static List<PartitionOffsets> defaultOffsets() {
		return List.of(new PartitionOffsets(0, 40L, 40L), new PartitionOffsets(1, 30L, 30L),
				new PartitionOffsets(2, 20L, 20L));
	}

}

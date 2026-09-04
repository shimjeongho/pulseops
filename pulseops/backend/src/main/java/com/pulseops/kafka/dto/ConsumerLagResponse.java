package com.pulseops.kafka.dto;

import com.pulseops.kafka.ConsumerLagPartitionState;
import com.pulseops.kafka.PartitionOffsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Consumer Lag 조회 응답 전체이다.
 * <p>
 * 이것은 지금 이 순간의 값이다. 어디에도 저장되지 않으며 이전 값과 비교되지도 않는다. 그래서 이 응답
 * 하나로 말할 수 있는 것은 "지금 밀려 있는 양"뿐이고, 밀림이 늘고 있는지 줄고 있는지는 말할 수 없다.
 * <p>
 * Lag은 관측값이며 Incident 판정에 쓰이지 않는다. Incident는 Demo Shop이 실제로 돌려준 응답의 오류율과
 * 응답 시간으로만 열린다. Lag이 크다는 것은 PulseOps가 Event를 늦게 읽고 있다는 뜻이지 Demo Shop이
 * 아프다는 뜻이 아니고, 반대로 Lag이 0이어도 Demo Shop은 얼마든지 아플 수 있다.
 *
 * @param generatedAt 이 값을 읽은 시각. partition마다 다시 읽지 않는다.
 * @param topic 조회 대상 Topic
 * @param consumerGroup 조회 대상 Consumer group
 * @param partitionCount Topic의 partition 수
 * @param uninitializedPartitionCount 아직 commit 기록이 없어 lag을 계산하지 못한 partition 수
 * @param totalLag lag을 계산할 수 있었던 partition의 합. 이 값만 보고 전체가 밀리지 않았다고 말할 수
 * 없어서 {@code uninitializedPartitionCount}를 같은 높이에 둔다. 그 수가 0보다 크면 합계는 Topic의
 * 일부만 설명한다.
 * @param partitions partition 번호 오름차순
 */
public record ConsumerLagResponse(Instant generatedAt, String topic, String consumerGroup, int partitionCount,
		int uninitializedPartitionCount, long totalLag, List<ConsumerLagPartitionResponse> partitions) {

	/**
	 * {@code initializedPartitionCount}와 {@code complete}는 두지 않는다. 앞의 것은 partition 수에서
	 * 뒤의 것을 뺀 값이고 뒤의 것은 {@code uninitializedPartitionCount == 0}이라, 같은 사실을 세 번 적는
	 * 셈이 된다. 한쪽만 어긋나는 응답을 만들 수 있는 자리를 애초에 만들지 않는다.
	 */
	public ConsumerLagResponse {
		partitions = List.copyOf(partitions);
	}

	/**
	 * partition 번호로 정렬한다. Broker가 돌려주는 순서는 metadata 사정이 정하는 것이라 조회할 때마다
	 * 달라질 수 있고, 5초마다 다시 읽는 화면에서 줄의 위치가 바뀌면 보고 있던 partition을 눈으로 따라갈
	 * 수 없다.
	 */
	public static ConsumerLagResponse from(Instant generatedAt, String topic, String consumerGroup,
			List<PartitionOffsets> offsets) {
		List<ConsumerLagPartitionResponse> partitions = offsets.stream()
			.map(ConsumerLagPartitionResponse::from)
			.sorted(Comparator.comparingInt(ConsumerLagPartitionResponse::partition))
			.toList();
		long totalLag = partitions.stream()
			.filter((partition) -> partition.lag() != null)
			.mapToLong(ConsumerLagPartitionResponse::lag)
			.sum();
		int uninitializedPartitionCount = (int) partitions.stream()
			.filter((partition) -> partition.state() == ConsumerLagPartitionState.UNINITIALIZED)
			.count();
		return new ConsumerLagResponse(generatedAt, topic, consumerGroup, partitions.size(),
				uninitializedPartitionCount, totalLag, partitions);
	}

}

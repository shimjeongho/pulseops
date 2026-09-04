package com.pulseops.kafka.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseops.kafka.ConsumerLagPartitionState;
import com.pulseops.kafka.PartitionOffsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 여러 partition의 offset이 하나의 응답으로 합쳐질 때의 규칙을 고정한다.
 */
class ConsumerLagResponseTest {

	private static final Instant GENERATED_AT = Instant.parse("2026-09-04T06:00:00Z");

	private static final String TOPIC = "demo-shop.telemetry.api.v1";

	private static final String CONSUMER_GROUP = "pulseops-metric-consumer-v1";

	@Test
	void addsUpTheLagOfEveryPartition() {
		ConsumerLagResponse response = from(new PartitionOffsets(0, 120L, 100L), new PartitionOffsets(1, 50L, 50L),
				new PartitionOffsets(2, 310L, 300L));

		assertThat(response.totalLag()).isEqualTo(30L);
		assertThat(response.partitionCount()).isEqualTo(3);
		assertThat(response.uninitializedPartitionCount()).isZero();
	}

	/**
	 * Broker가 돌려주는 순서는 metadata 사정이 정하는 것이라 조회할 때마다 달라질 수 있다. 5초마다 다시
	 * 읽는 화면에서 줄의 위치가 바뀌면 보고 있던 partition을 눈으로 따라갈 수 없다.
	 */
	@Test
	void ordersPartitionsByNumberWhateverOrderKafkaAnswersIn() {
		ConsumerLagResponse response = from(new PartitionOffsets(2, 10L, 10L), new PartitionOffsets(0, 10L, 10L),
				new PartitionOffsets(1, 10L, 10L));

		assertThat(response.partitions()).extracting(ConsumerLagPartitionResponse::partition)
			.containsExactly(0, 1, 2);
	}

	/**
	 * 합계는 셀 수 있었던 partition만 더한 값이다. 세지 못한 partition을 0으로 채워 넣으면 합계가 Topic
	 * 전체를 설명하는 것처럼 보인다.
	 */
	@Test
	void countsUninitializedPartitionsSeparatelyInsteadOfAddingThemAsZero() {
		ConsumerLagResponse response = from(new PartitionOffsets(0, 120L, 100L), new PartitionOffsets(1, 900L, null),
				new PartitionOffsets(2, 300L, 300L));

		assertThat(response.totalLag()).isEqualTo(20L);
		assertThat(response.uninitializedPartitionCount()).isEqualTo(1);
		assertThat(response.partitionCount()).isEqualTo(3);
		assertThat(response.partitions().get(1).state()).isEqualTo(ConsumerLagPartitionState.UNINITIALIZED);
	}

	/**
	 * 아직 아무 partition도 읽지 않은 group은 totalLag이 0으로 나온다. 그래서 그 숫자 하나로는 상태를
	 * 말할 수 없고, 그 사실이 응답에 함께 담겨야 화면이 다르게 그릴 수 있다.
	 */
	@Test
	void marksEveryPartitionUninitializedForAGroupThatNeverCommitted() {
		ConsumerLagResponse response = from(new PartitionOffsets(0, 120L, null), new PartitionOffsets(1, 80L, null));

		assertThat(response.totalLag()).isZero();
		assertThat(response.uninitializedPartitionCount()).isEqualTo(2);
	}

	@Test
	void answersWithTheTopicAndGroupItWasAskedAbout() {
		ConsumerLagResponse response = from(new PartitionOffsets(0, 1L, 1L));

		assertThat(response.topic()).isEqualTo(TOPIC);
		assertThat(response.consumerGroup()).isEqualTo(CONSUMER_GROUP);
		assertThat(response.generatedAt()).isEqualTo(GENERATED_AT);
	}

	/** partition이 없는 Topic도 실패가 아니다. 답할 것이 없다는 것과 답하지 못하는 것은 다르다. */
	@Test
	void answersWithAnEmptySnapshotForATopicWithoutPartitions() {
		ConsumerLagResponse response = ConsumerLagResponse.from(GENERATED_AT, TOPIC, CONSUMER_GROUP, List.of());

		assertThat(response.partitions()).isEmpty();
		assertThat(response.partitionCount()).isZero();
		assertThat(response.totalLag()).isZero();
		assertThat(response.uninitializedPartitionCount()).isZero();
	}

	/** 합계도 partition 값도 long이다. 어느 한 자리라도 int로 좁히면 오래된 Topic에서 값이 무너진다. */
	@Test
	void addsUpOffsetsBeyondTheIntegerRange() {
		ConsumerLagResponse response = from(new PartitionOffsets(0, 3_000_000_000L, 2_000_000_000L),
				new PartitionOffsets(1, 3_000_000_000L, 2_500_000_000L));

		assertThat(response.totalLag()).isEqualTo(1_500_000_000L);
	}

	private static ConsumerLagResponse from(PartitionOffsets... offsets) {
		return ConsumerLagResponse.from(GENERATED_AT, TOPIC, CONSUMER_GROUP, List.of(offsets));
	}

}

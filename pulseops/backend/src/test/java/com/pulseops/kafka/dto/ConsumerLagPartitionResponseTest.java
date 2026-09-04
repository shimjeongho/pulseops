package com.pulseops.kafka.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseops.kafka.ConsumerLagPartitionState;
import com.pulseops.kafka.PartitionOffsets;
import org.junit.jupiter.api.Test;

/**
 * partition 하나의 lag이 offset 두 개에서 어떻게 나오는지를 고정한다.
 * <p>
 * Kafka 없이 확인한다. 여기서 보는 것은 Broker가 무엇을 알려 주는지가 아니라 그 값을 받아 무엇으로
 * 읽는지이며, 그 규칙은 전부 우리가 정한 것이다.
 */
class ConsumerLagPartitionResponseTest {

	/**
	 * committed offset은 "다음에 읽을 offset"이므로 log end offset과 같으면 다 따라잡은 것이다. 여기서
	 * 1을 더하거나 빼면 늘 한 건씩 어긋난 lag이 나온다.
	 */
	@Test
	void reportsNoLagWhenTheGroupHasCaughtUp() {
		ConsumerLagPartitionResponse partition = ConsumerLagPartitionResponse
			.from(new PartitionOffsets(0, 100L, 100L));

		assertThat(partition.lag()).isZero();
		assertThat(partition.state()).isEqualTo(ConsumerLagPartitionState.INITIALIZED);
	}

	@Test
	void reportsTheDistanceBetweenTheTwoOffsets() {
		ConsumerLagPartitionResponse partition = ConsumerLagPartitionResponse
			.from(new PartitionOffsets(0, 120L, 100L));

		assertThat(partition.lag()).isEqualTo(20L);
		assertThat(partition.logEndOffset()).isEqualTo(120L);
		assertThat(partition.committedOffset()).isEqualTo(100L);
	}

	/**
	 * 두 offset은 서로 다른 요청으로 읽으므로 그 사이에 Consumer가 진행하면 committed 쪽이 더 큰 순간이
	 * 관측될 수 있다. 그것은 밀린 것이 음수라는 뜻이 아니라 따라잡았다는 뜻이다.
	 */
	@Test
	void neverReportsANegativeLagWhenTheCommittedOffsetRacesAhead() {
		ConsumerLagPartitionResponse partition = ConsumerLagPartitionResponse
			.from(new PartitionOffsets(0, 100L, 103L));

		assertThat(partition.lag()).isZero();
	}

	/** 보정한 것은 lag뿐이다. 읽어 온 offset을 함께 고치면 화면의 두 숫자로 lag을 다시 계산할 수 없다. */
	@Test
	void keepsTheRawOffsetsEvenWhenTheirDifferenceIsNegative() {
		ConsumerLagPartitionResponse partition = ConsumerLagPartitionResponse
			.from(new PartitionOffsets(0, 100L, 103L));

		assertThat(partition.logEndOffset()).isEqualTo(100L);
		assertThat(partition.committedOffset()).isEqualTo(103L);
	}

	/**
	 * 한 번도 commit하지 않은 partition을 lag 0으로 만들지 않는다. 그렇게 하면 Consumer가 붙지도 않은
	 * 구간이 가장 건강한 구간으로 보인다.
	 */
	@Test
	void refusesToCallAnUncommittedPartitionCaughtUp() {
		ConsumerLagPartitionResponse partition = ConsumerLagPartitionResponse.from(new PartitionOffsets(0, 500L, null));

		assertThat(partition.state()).isEqualTo(ConsumerLagPartitionState.UNINITIALIZED);
		assertThat(partition.lag()).isNull();
		assertThat(partition.committedOffset()).isNull();
		assertThat(partition.logEndOffset()).isEqualTo(500L);
	}

	/**
	 * offset은 Topic이 오래 살아 있으면 int 범위를 넘는다. 어느 자리에서든 int로 좁히면 그 순간부터 lag이
	 * 음수나 엉뚱한 값이 된다.
	 */
	@Test
	void handlesOffsetsBeyondTheIntegerRange() {
		long logEndOffset = 9_000_000_050L;
		long committedOffset = 9_000_000_000L;

		ConsumerLagPartitionResponse partition = ConsumerLagPartitionResponse
			.from(new PartitionOffsets(0, logEndOffset, committedOffset));

		assertThat(partition.lag()).isEqualTo(50L);
		assertThat(partition.logEndOffset()).isEqualTo(logEndOffset);
	}

}

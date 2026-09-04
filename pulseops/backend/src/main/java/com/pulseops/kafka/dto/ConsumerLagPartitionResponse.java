package com.pulseops.kafka.dto;

import com.pulseops.kafka.ConsumerLagPartitionState;
import com.pulseops.kafka.PartitionOffsets;

/**
 * 한 partition의 lag이다.
 * <p>
 * 계산에 쓴 offset 두 개를 함께 내보낸다. lag은 두 값의 차이일 뿐이라, 숫자 하나만 보여 주면 그것이
 * 어떤 두 지점 사이의 거리인지 화면에서 확인할 방법이 없다.
 *
 * @param partition partition 번호
 * @param logEndOffset 다음으로 append될 offset
 * @param committedOffset Consumer group이 다음에 읽을 offset. commit 기록이 없으면 null이다.
 * @param lag 아직 읽지 않은 Record 수. committed offset이 없으면 null이며 0이 아니다.
 * @param state lag을 계산했는지 여부
 */
public record ConsumerLagPartitionResponse(int partition, long logEndOffset, Long committedOffset, Long lag,
		ConsumerLagPartitionState state) {

	/**
	 * committed offset이 없는 partition을 lag 0으로 만들지 않는다. 한 번도 읽지 않은 partition은 밀린
	 * 정도를 말할 수 없는 상태이지 밀리지 않은 상태가 아니며, 0으로 적으면 Consumer가 붙지도 않은 구간이
	 * 가장 건강한 구간으로 보인다.
	 * <p>
	 * 차이가 음수면 0으로 맞춘다. log end offset과 committed offset은 서로 다른 시점에 서로 다른 요청으로
	 * 읽으므로, 그 사이에 Consumer가 진행하면 committed 쪽이 더 큰 순간이 관측될 수 있다. 그것은 "-3개
	 * 남았다"가 아니라 "따라잡았다"는 뜻이다. 다만 읽어 온 offset 원본은 보정하지 않고 그대로 내보낸다.
	 * 보정하면 화면에 적힌 두 숫자로 lag을 다시 계산할 수 없게 된다.
	 */
	public static ConsumerLagPartitionResponse from(PartitionOffsets offsets) {
		Long committedOffset = offsets.committedOffset();
		if (committedOffset == null) {
			return new ConsumerLagPartitionResponse(offsets.partition(), offsets.logEndOffset(), null, null,
					ConsumerLagPartitionState.UNINITIALIZED);
		}
		long lag = Math.max(0, offsets.logEndOffset() - committedOffset);
		return new ConsumerLagPartitionResponse(offsets.partition(), offsets.logEndOffset(), committedOffset, lag,
				ConsumerLagPartitionState.INITIALIZED);
	}

}

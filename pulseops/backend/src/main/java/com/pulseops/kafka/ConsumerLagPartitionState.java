package com.pulseops.kafka;

/**
 * 한 partition의 lag을 계산할 수 있었는지를 뜻한다.
 * <p>
 * lag이 null인 것과 0인 것을 화면과 API 모두에서 분명히 갈라 놓기 위해 둔다. 두 값을 같은 자리에서
 * 숫자로만 표현하면 "아직 한 번도 읽지 않았다"가 "밀린 것이 없다"로 읽히고, 그것은 Consumer가 아예
 * 붙지 않은 상태를 가장 건강한 상태로 보여 주는 것이다.
 */
public enum ConsumerLagPartitionState {

	/** Consumer group에 이 partition의 committed offset이 있어 lag을 계산했다. */
	INITIALIZED,

	/** Consumer group이 이 partition을 아직 한 번도 commit하지 않아 lag을 계산할 수 없다. */
	UNINITIALIZED

}

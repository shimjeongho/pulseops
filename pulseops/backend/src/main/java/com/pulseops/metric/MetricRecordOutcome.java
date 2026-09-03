package com.pulseops.metric;

/**
 * Telemetry Event 하나가 실시간 Metric에 어떻게 반영됐는지를 나타낸다.
 * <p>
 * boolean 하나로는 부족하다. Kafka 재전달로 이미 반영된 Event와 집계 창을 벗어난 Event는 둘 다
 * "Metric이 달라지지 않았다"는 점에서 같지만, 뒤따르는 처리에서는 서로 다르게 다뤄야 한다.
 * 재전달은 앞선 시도가 Metric까지만 끝내고 중단됐을 수 있다는 뜻이라 남은 처리를 이어가야 하고,
 * 창 밖 Event는 어떤 창에도 들어가지 않으므로 이어갈 처리가 없다.
 * <p>
 * Redis 장애는 여기에 없다. 반영 실패는 결과가 아니라 오류이며 Kafka 재시도로 다시 처리해야 하므로
 * 값으로 돌려주지 않고 예외로 올려 보낸다.
 */
public enum MetricRecordOutcome {

	/** 이번 호출이 Metric을 실제로 바꿨다. */
	APPLIED,

	/** 같은 eventId가 이미 반영돼 있어 아무것도 바꾸지 않았다. */
	DUPLICATE,

	/** 집계 창을 벗어난 Event라 Redis에 닿기 전에 끝났다. */
	OUT_OF_WINDOW

}

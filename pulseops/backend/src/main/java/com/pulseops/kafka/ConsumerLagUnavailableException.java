package com.pulseops.kafka;

/**
 * 지금은 Kafka Consumer Lag을 조회할 수 없다는 뜻이다.
 * <p>
 * Broker에 닿지 못한 경우, 정해진 시간 안에 답을 받지 못한 경우, Topic이 아직 없는 경우, 그리고 이
 * 구성에서 Kafka에 붙지 않는 경우를 모두 이 하나로 모은다. Client가 그것들을 나눠 받아도 할 수 있는
 * 일이 같기 때문이다. 전부 "지금은 답할 수 없으니 다음 주기에 다시"이다.
 * <p>
 * 이 실패를 빈 partition 목록과 {@code totalLag=0}으로 바꾸지 않는다. 그렇게 하면 Broker 장애가 화면에서
 * 가장 건강한 상태로 보인다.
 * <p>
 * {@code reason}은 log에만 쓰는 짧은 고정 문자열이며 응답에는 나가지 않는다. Kafka 예외의 message에는
 * Broker 주소나 Topic, 내부 요청 이름이 그대로 담겨 오므로 밖으로 내보내지 않는다.
 */
public class ConsumerLagUnavailableException extends RuntimeException {

	private final String reason;

	public ConsumerLagUnavailableException(String reason, Throwable cause) {
		super("Kafka consumer lag unavailable: " + reason, cause);
		this.reason = reason;
	}

	public String getReason() {
		return this.reason;
	}

}

package com.pulseops.incident;

/**
 * Incident를 연 이상의 종류이다.
 * <p>
 * 이름은 판정에 쓰인 Metric과 같게 둔다. 그래야 규칙과 기록 사이에 번역이 생기지 않는다. 오류율과
 * 응답 시간은 회복 조건도 다르고 대응도 다르므로, 한 계열에서 둘이 동시에 나빠지면 각각 별도의
 * Incident가 된다.
 */
public enum IncidentType {

	ERROR_RATE,
	LATENCY

}

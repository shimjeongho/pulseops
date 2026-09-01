package com.pulseops.incident;

/**
 * Incident의 처리 단계이다.
 * <p>
 * 허용되는 전이는 OPEN에서 ACKNOWLEDGED, OPEN에서 RESOLVED, ACKNOWLEDGED에서 RESOLVED뿐이다.
 * RESOLVED는 되돌릴 수 없는 마지막 단계이며, 같은 문제가 다시 관측되면 이 기록을 다시 열지 않고
 * 새 Incident를 만든다. 한 번 끝난 장애의 기록이 나중에 다시 열리면 그 장애가 언제부터 언제까지
 * 였는지 말할 수 없게 된다.
 * <p>
 * 전이 규칙 자체는 {@link Incident}가 지킨다. 상태 값만으로는 어떤 전이가 일어났는지 남지 않기
 * 때문이다.
 */
public enum IncidentStatus {

	OPEN,
	ACKNOWLEDGED,
	RESOLVED

}

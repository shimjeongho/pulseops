package com.pulseops.incident;

/**
 * Incident가 얼마나 나쁜 상태인지를 나타낸다.
 * <p>
 * 회복은 심각도가 아니라 {@link IncidentStatus}로 표현한다. 상태와 심각도를 한 축에 섞으면
 * "심각한데 해결됨" 같은 조합을 표현할 수 없거나 서로 모순되는 값이 생긴다. 사람이 볼 가치가 없는
 * 단계는 애초에 Incident를 열지 않으므로 정보성 단계도 두지 않는다.
 */
public enum IncidentSeverity {

	WARNING,
	CRITICAL;

	/**
	 * 이 심각도가 {@code other}보다 높은지 판단한다. 선언 순서가 곧 심각도의 순서이다.
	 * <p>
	 * 비교를 이 안에 두는 이유는, 심각도의 순서를 아는 곳이 여기 하나여야 하기 때문이다. 밖에서
	 * 순서를 직접 비교하기 시작하면 값이 늘어날 때 고쳐야 할 자리가 흩어진다.
	 */
	public boolean isHigherThan(IncidentSeverity other) {
		return compareTo(other) > 0;
	}

}

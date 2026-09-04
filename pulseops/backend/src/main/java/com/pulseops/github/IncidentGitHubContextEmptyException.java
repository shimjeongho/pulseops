package com.pulseops.github;

/**
 * 맥락은 모았지만 관련 source를 하나도 찾지 못해 코드 기반 분석을 만들 수 없다는 뜻이다.
 * <p>
 * 실패가 아니라 조건이 갖춰지지 않은 상태라서 따로 둔다. 이 상태에서 분석을 만들면 그것은 이름만
 * "코드 기반"일 뿐 기존 분석과 같은 근거로 만든 두 번째 분석이 되고, 두 분석의 차이를 설명할 수 없게
 * 된다. 요금도 근거 없이 한 번 더 든다.
 * <p>
 * 수집을 다시 하라는 뜻도 아니다. 맥락은 이미 있고, 그 맥락이 말하는 사실이 "찾지 못했다"이다.
 */
public class IncidentGitHubContextEmptyException extends RuntimeException {

	public IncidentGitHubContextEmptyException(Long incidentId) {
		super("Incident GitHub context has no source file: " + incidentId);
	}

}

package com.pulseops.github;

/**
 * 그 Incident에는 아직 GitHub 코드 맥락이 없다는 뜻이다.
 * <p>
 * Incident가 없는 것과 다르다. Incident는 있는데 아무도 수집을 요청하지 않았을 뿐이며, 그것은 정상적인
 * 상태이다. 둘을 같은 code로 답하면 화면은 수집 버튼을 보여 줘야 할 자리에 Incident를 찾을 수 없다는
 * 문구를 그린다.
 * <p>
 * 코드 기반 AI 분석을 요청했는데 맥락이 없을 때도 이 예외이다. 그 경우 다음에 할 일은 분석이 아니라
 * 수집이며, 그것을 알리는 사실은 여기서도 같다.
 */
public class IncidentGitHubContextNotFoundException extends RuntimeException {

	public IncidentGitHubContextNotFoundException(Long incidentId) {
		super("Incident GitHub context not found: " + incidentId);
	}

}

package com.pulseops.github;

/**
 * Incident와 관련 있는 코드 맥락을 외부 저장소에서 모아 오는 경계이다.
 * <p>
 * 이 interface가 있는 이유는 나중에 GitLab을 붙일지 몰라서가 아니라, 외부 network 호출을 한 자리에
 * 가두기 위해서이다. {@link IncidentGitHubContextService}는 언제 모으고 어디에 저장할지를 정하는 곳이고,
 * HTTP header나 GitHub의 JSON 모양은 그 판단과 아무 관계가 없다. 둘을 한 Class에 두면 저장 규칙을
 * 확인하려는 테스트가 매번 HTTP를 흉내 내야 한다.
 * <p>
 * 기능이 꺼져 있으면 이 bean이 아예 없다. Phase 9의 분석 Client와 같은 방식이며, "지금 GitHub를 부를 수
 * 있는가"가 어느 method 안의 분기가 아니라 Application의 구조로 드러난다.
 * <p>
 * 실패는 전부 {@link IncidentGitHubContextUnavailableException} 하나로 나온다. 부르는 쪽이 인증 오류와
 * 없는 repository와 한도 초과를 두고 할 수 있는 일이 같기 때문이다. 셋 다 이번에는 맥락을 모을 수
 * 없다는 뜻이고, 아무것도 저장하지 않은 채 알린 뒤 다시 시도하게 하는 것 말고 다른 대응이 없다.
 * 무엇이 실제로 잘못됐는지는 응답이 아니라 server log가 남긴다.
 */
public interface IncidentGitHubContextClient {

	/**
	 * 관련 source 조각과 그 직전 commit을 모아 온다.
	 * <p>
	 * 관련 source를 찾지 못해도 실패가 아니다. 그때는 비어 있는 맥락을 돌려주며, 그것도 "찾아봤지만
	 * 없었다"는 기록으로 남을 값어치가 있는 결과이다.
	 * @throws IncidentGitHubContextUnavailableException 호출에 실패했거나 응답을 쓸 수 없을 때
	 */
	GitHubCodeContext collect(IncidentGitHubContextQuery query);

}

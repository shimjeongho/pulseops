package com.pulseops.analysis;

import com.pulseops.github.GitHubCodeContext;

/**
 * Incident 사실을 외부 AI에게 보내 분석을 받아 오는 경계이다.
 * <p>
 * 이 interface가 있는 이유는 나중에 다른 provider를 붙일지 몰라서가 아니라, 외부 network 호출을 한
 * 자리에 가두기 위해서이다. {@link IncidentAiAnalysisService}는 언제 분석을 만들고 어디에 저장할지를
 * 정하는 곳이고, HTTP header나 provider의 JSON 모양은 그 판단과 아무 관계가 없다. 둘을 한 Class에 두면
 * 저장 규칙을 확인하려는 테스트가 매번 HTTP를 흉내 내야 한다.
 * <p>
 * 실패는 전부 {@link IncidentAnalysisUnavailableException} 하나로 나온다. 부르는 쪽이 timeout과 인증
 * 오류와 깨진 응답을 두고 할 수 있는 일이 같기 때문이다. 셋 다 이번에는 분석을 만들 수 없다는 뜻이고,
 * 저장하지 않은 채 사용자에게 알린 뒤 다시 시도하게 하는 것 말고 다른 대응이 없다. 무엇이 실제로
 * 잘못됐는지는 응답이 아니라 server log가 남긴다.
 */
public interface IncidentAnalysisClient {

	/**
	 * 사실을 넘겨 분석 하나를 받는다.
	 * <p>
	 * 넘긴 사실을 그대로 결과에 담아 돌려준다. 저장해야 할 snapshot은 "보낸 것"이어야 하는데, 그것을
	 * 실제로 만든 자리가 여기이기 때문이다. 부르는 쪽이 같은 값을 따로 직렬화하면 두 문자열이 언젠가
	 * 어긋나고, 그때부터 snapshot은 보낸 것이 아니라 보냈을 법한 것이 된다.
	 * @throws IncidentAnalysisUnavailableException 호출에 실패했거나 응답을 분석으로 쓸 수 없을 때
	 */
	IncidentAnalysisResult analyze(IncidentAnalysisFacts facts);

	/**
	 * 사실과 코드 맥락을 함께 넘겨 분석 하나를 받는다.
	 * <p>
	 * 맥락을 인자로 받는다. 이 경계가 GitHub을 직접 부르지 않는다는 뜻이며, 그래서 분석을 몇 번 만들든
	 * 외부 저장소 호출은 한 번도 늘지 않는다. 무엇을 근거로 삼을지는 이미 저장된 맥락이 정해 두었고,
	 * 여기서 다시 찾아 나서면 같은 Incident의 두 분석이 서로 다른 코드를 보고 만들어질 수 있다.
	 * <p>
	 * 돌려주는 snapshot은 여전히 Incident 사실 쪽이다. 코드 맥락은 이미 별도의 기록으로 저장되어 있고,
	 * 그것을 분석 행에 한 번 더 복사해 두면 언젠가 두 값이 서로 다른 것을 가리킨다.
	 * @throws IncidentAnalysisUnavailableException 호출에 실패했거나 응답을 분석으로 쓸 수 없을 때
	 */
	IncidentAnalysisResult analyzeWithCode(IncidentAnalysisFacts facts, GitHubCodeContext code);

}

package com.pulseops.analysis;

/**
 * 그 Incident에는 아직 AI 분석이 없다는 뜻이다.
 * <p>
 * Incident가 없는 것과 다르다. Incident는 있는데 아무도 분석을 요청하지 않았을 뿐이며, 그것은 정상적인
 * 상태이다. 둘을 같은 code로 답하면 화면은 "없는 Incident"와 "아직 분석하지 않은 Incident"를 구분하지
 * 못해, 분석 버튼을 보여 줘야 할 자리에 Incident를 찾을 수 없다는 문구를 그린다.
 * <p>
 * 조회에서만 쓴다. 생성 요청에서 분석이 없는 것은 실패가 아니라 지금부터 만들 조건이다.
 */
public class IncidentAiAnalysisNotFoundException extends RuntimeException {

	public IncidentAiAnalysisNotFoundException(Long incidentId) {
		super("Incident AI analysis not found: " + incidentId);
	}

}

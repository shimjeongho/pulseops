package com.pulseops.analysis.dto;

import com.pulseops.analysis.AnalysisConfidence;
import com.pulseops.analysis.IncidentAiAnalysis;
import java.time.Instant;
import java.util.List;

/**
 * Incident 하나의 AI 분석을 Client가 읽는 형태이다.
 * <p>
 * Entity를 그대로 내보내지 않는다. 저장에 필요한 값과 화면에 필요한 값이 다르고, Entity를 내보내면
 * 저장 구조를 바꾸는 것이 곧 API 계약을 바꾸는 일이 된다.
 * <p>
 * model과 generatedAt을 담는다. 이 문장들이 언제 어떤 model이 만든 것인지 모르면, 읽는 사람은 그것을
 * 지금 시점의 판단으로 읽는다. 분석은 만들어진 뒤 바뀌지 않으므로 시각은 그 자체로 "이 분석이 무엇을
 * 알 수 있었는가"의 상한이다.
 * <p>
 * promptVersion과 inputSnapshot은 담지 않는다. 두 값은 나중에 이 분석이 어떤 조건에서 만들어졌는지
 * 되짚기 위한 것이며 화면이 할 일이 없다. 특히 snapshot은 그때의 Incident 값이라, 화면에 함께 나가면
 * 지금의 Incident 상세와 나란히 놓여 두 숫자 중 어느 것이 현재인지 헷갈린다.
 * <p>
 * 이 분석이 확정된 원인이 아니라는 안내는 여기 담지 않는다. 모든 응답에 같은 문장을 실어 보내면 그것은
 * 데이터가 아니라 화면 문구를 서버가 대신 들고 다니는 것이고, 그 문장을 읽고 할 수 있는 일은 화면에
 * 그리는 것뿐이다. 대신 그 안내를 반드시 보이게 하는 책임은 화면에 있다.
 */
public record IncidentAiAnalysisResponse(Long incidentId, String summary, String primaryHypothesis,
		AnalysisConfidence confidence, List<String> evidence, List<String> recommendedActions, List<String> limitations,
		String model, Instant generatedAt) {

	public static IncidentAiAnalysisResponse from(IncidentAiAnalysis analysis) {
		return new IncidentAiAnalysisResponse(analysis.getIncidentId(), analysis.getSummary(),
				analysis.getPrimaryHypothesis(), analysis.getConfidence(), analysis.getEvidence(),
				analysis.getRecommendedActions(), analysis.getLimitations(), analysis.getModel(),
				analysis.getGeneratedAt());
	}

}

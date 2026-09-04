package com.pulseops.analysis;

import java.util.List;

/**
 * Claude가 돌려준 분석 본문을 그대로 읽은 형태이다. 아직 검증되지 않았다.
 * <p>
 * {@link IncidentAnalysisResult}와 나누는 이유는 confidence 때문이다. 여기서는 문자열로 받는다.
 * 곧바로 enum으로 읽게 하면 모르는 값이 왔을 때 Jackson이 던지는 실패와 우리가 정한 검증 실패가
 * 뒤섞이고, 어느 쪽이 계약 위반인지 코드에서 구분되지 않는다.
 * <p>
 * 이 record로 읽을 때는 모르는 field를 허용하지 않는다. 이 구조는 우리가 Schema로 요구한 계약이므로,
 * 없던 field가 왔다는 것은 계약이 지켜지지 않았다는 뜻이다. 조용히 버리면 그 어긋남이 드러나지 않는다.
 */
public record ClaudeAnalysisPayload(String summary, String primaryHypothesis, String confidence,
		List<String> evidence, List<String> recommendedActions, List<String> limitations) {

}

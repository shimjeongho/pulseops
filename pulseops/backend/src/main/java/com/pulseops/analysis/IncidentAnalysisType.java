package com.pulseops.analysis;

/**
 * AI 분석이 무엇을 보고 만들어졌는지를 나타낸다.
 * <p>
 * 두 분석의 근거가 다르기 때문에 나눈다. {@link #INCIDENT_ONLY}는 PulseOps가 스스로 관측해 저장한 판정
 * 근거만 보고 만든 것이고, {@link #GITHUB_ENRICHED}는 거기에 GitHub에서 모아 온 소스 조각과 최근 commit을
 * 더해 만든 것이다. 어느 쪽을 읽고 있는지 모르면 같은 문장을 두고 얼마나 믿어야 할지도 알 수 없다.
 * <p>
 * 결과의 좋고 나쁨을 뜻하지 않는다. 코드를 함께 본 분석이 언제나 더 맞는 것은 아니며, 관련 없는 파일을
 * 근거로 삼았다면 오히려 그럴듯하게 틀린다. 이 값이 말하는 것은 정확도가 아니라 무엇이 입력이었는가이다.
 * <p>
 * 종류를 더 늘리지 않는다. 늘리는 순간 Incident 하나에 분석이 여러 개 쌓이고, 사람은 그중 어느 것을
 * 읽어야 하는지 정할 방법이 없어진다.
 */
public enum IncidentAnalysisType {

	INCIDENT_ONLY,
	GITHUB_ENRICHED

}

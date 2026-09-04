package com.pulseops.analysis;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * AI 분석의 영속화를 담당한다.
 * <p>
 * 잠그는 조회가 없다. 이 기록은 한 번 만들어지면 바뀌지 않으므로 읽은 값을 근거로 무엇을 바꾸는 흐름이
 * 없고, 같은 Incident의 같은 종류를 두 번 만들려는 경쟁은 {@code (incident_id, analysis_type)}의 UNIQUE가
 * 막는다. 잠금으로 막으려 들면 아직 없는 행을 잠가야 하는데, 그러려면 외부 API를 기다리는 동안 그
 * 잠금을 들고 있어야 한다.
 * <p>
 * Incident를 함께 읽는 조회를 두지 않는다. 이 Entity에는 Incident 연관이 없으므로 조회 하나에 SQL도
 * 하나이며, 목록 조회가 없어 N+1이 생길 자리 자체가 없다.
 */
public interface IncidentAiAnalysisRepository extends JpaRepository<IncidentAiAnalysis, Long> {

	/**
	 * Incident 하나의 어떤 종류의 분석을 찾는다.
	 * <p>
	 * 종류를 언제나 함께 받는다. Incident만으로 찾으면 판정 근거만 본 분석과 코드 맥락까지 본 분석 중
	 * 어느 것이 나올지 정해지지 않고, 화면이 기본 분석을 물었는데 코드 기반 분석이 돌아오는 일이
	 * 생긴다. UNIQUE가 이 조합에 걸려 있어 결과는 언제나 하나이거나 없다.
	 */
	Optional<IncidentAiAnalysis> findByIncidentIdAndAnalysisType(Long incidentId, IncidentAnalysisType analysisType);

}

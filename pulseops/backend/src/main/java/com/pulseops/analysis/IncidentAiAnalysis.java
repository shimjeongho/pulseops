package com.pulseops.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;

/**
 * Incident 하나에 대해 AI가 만든 사후 분석이다.
 * <p>
 * 만들어진 뒤에는 바뀌지 않는다. 상태를 바꾸는 method가 하나도 없는 것이 그 뜻이다. 이 기록은
 * {@code generatedAt} 시점에 {@code inputSnapshot}을 보고 만들어진 하나의 추론이며, 나중에 Incident가
 * 확인되거나 해결되어도 여기의 문장은 움직이지 않는다. 움직이게 만들면 어제 읽은 분석과 오늘 읽는
 * 분석이 달라져 어느 쪽을 근거로 이야기했는지 말할 수 없게 되고, 그때마다 외부 API 호출 비용이 다시 든다.
 * <p>
 * 여기에 담긴 것은 관측이 아니라 가설이다. Incident의 trigger 값들은 판정이 실제로 본 숫자이지만, 이
 * 기록의 문장은 그 숫자를 읽은 모델이 지어낸 설명이다. 그래서 Incident에 열을 더하지 않고 별도 Entity로
 * 둔다. 한 행에 섞이면 두 성격의 값이 같은 무게로 읽힌다.
 * <p>
 * Incident를 연관으로 두지 않고 {@code incidentId}만 갖는다. 이 분석이 Incident의 어떤 값도 읽지 않기
 * 때문이다. 분석에 쓰인 값은 이미 {@code inputSnapshot}에 굳어 있고, 지금의 Incident는 그때와 다를 수
 * 있어 오히려 읽으면 안 된다. 연관을 두면 조회마다 Incident를 함께 끌고 오거나 지연 로딩 경계를 신경
 * 써야 하는데, 얻는 것이 없다. 없는 Incident를 가리킬 수 없다는 것은 DB의 FK가 지킨다.
 * <p>
 * Schema는 Flyway가 관리한다. Incident당 성공한 분석이 하나뿐인 것은 {@code incident_id}의 UNIQUE가
 * 최종적으로 보장한다.
 */
@Entity
@Table(name = "incident_ai_analysis")
public class IncidentAiAnalysis {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "incident_id", nullable = false)
	private Long incidentId;

	/** 설정에 적힌 이름이 아니라 응답이 알려 준, 실제로 이 분석을 만든 model이다. */
	@Column(name = "model", nullable = false, length = 100)
	private String model;

	@Column(name = "prompt_version", nullable = false, length = 50)
	private String promptVersion;

	/** 분석 당시 실제로 보낸 Incident 사실이다. 지금의 Incident 값이 아니다. */
	@Column(name = "input_snapshot", nullable = false, length = 2000)
	private String inputSnapshot;

	@Column(name = "summary", nullable = false, length = IncidentAnalysisResult.SUMMARY_MAX_LENGTH)
	private String summary;

	@Column(name = "primary_hypothesis", nullable = false,
			length = IncidentAnalysisResult.PRIMARY_HYPOTHESIS_MAX_LENGTH)
	private String primaryHypothesis;

	@Enumerated(EnumType.STRING)
	@Column(name = "confidence", nullable = false, length = 10)
	private AnalysisConfidence confidence;

	@Convert(converter = StringListJsonConverter.class)
	@Column(name = "evidence", nullable = false, length = 2500)
	private List<String> evidence;

	@Convert(converter = StringListJsonConverter.class)
	@Column(name = "recommended_actions", nullable = false, length = 2500)
	private List<String> recommendedActions;

	@Convert(converter = StringListJsonConverter.class)
	@Column(name = "limitations", nullable = false, length = 2500)
	private List<String> limitations;

	@Column(name = "generated_at", nullable = false)
	private Instant generatedAt;

	protected IncidentAiAnalysis() {
		// JPA 전용 기본 생성자
	}

	/**
	 * 검증을 통과한 분석 결과를 기록으로 굳힌다.
	 * <p>
	 * 문자열을 낱개로 받지 않고 {@link IncidentAnalysisResult}를 통째로 받는다. summary와
	 * primaryHypothesis처럼 같은 Type의 인자가 늘어서면 순서가 뒤바뀌어도 compile되고, 요약 자리에 가설이
	 * 들어간 기록이 남는다. 값이 계약을 지켰는지는 그 record가 만들어질 때 이미 확인되었으므로 여기서
	 * 다시 검사하지 않는다.
	 * <p>
	 * {@code generatedAt}은 호출자가 넘긴다. 여기서 시계를 읽으면 그 시각을 테스트가 정할 수 없다.
	 */
	public static IncidentAiAnalysis of(Long incidentId, IncidentAnalysisResult result, Instant generatedAt) {
		IncidentAiAnalysis analysis = new IncidentAiAnalysis();
		analysis.incidentId = incidentId;
		analysis.model = result.model();
		analysis.promptVersion = result.promptVersion();
		analysis.inputSnapshot = result.inputSnapshot();
		analysis.summary = result.summary();
		analysis.primaryHypothesis = result.primaryHypothesis();
		analysis.confidence = result.confidence();
		analysis.evidence = result.evidence();
		analysis.recommendedActions = result.recommendedActions();
		analysis.limitations = result.limitations();
		analysis.generatedAt = generatedAt;
		return analysis;
	}

	public Long getId() {
		return this.id;
	}

	public Long getIncidentId() {
		return this.incidentId;
	}

	public String getModel() {
		return this.model;
	}

	public String getPromptVersion() {
		return this.promptVersion;
	}

	public String getInputSnapshot() {
		return this.inputSnapshot;
	}

	public String getSummary() {
		return this.summary;
	}

	public String getPrimaryHypothesis() {
		return this.primaryHypothesis;
	}

	public AnalysisConfidence getConfidence() {
		return this.confidence;
	}

	public List<String> getEvidence() {
		return this.evidence;
	}

	public List<String> getRecommendedActions() {
		return this.recommendedActions;
	}

	public List<String> getLimitations() {
		return this.limitations;
	}

	public Instant getGeneratedAt() {
		return this.generatedAt;
	}

}

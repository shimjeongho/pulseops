package com.pulseops.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 저장할 수 있는 분석의 조건을 고정한다.
 * <p>
 * 여기서 막지 못한 값은 그대로 영구 기록이 된다. 모델이 돌려주는 문장은 우리가 정한 것이 아니므로,
 * 무엇을 받아들일지는 이 자리에서만 정해져 있어야 한다.
 */
class IncidentAnalysisResultTest {

	private static final String MODEL = "claude-opus-5";

	private static final String SNAPSHOT = "{\"service\":\"demo-shop\"}";

	@Test
	void acceptsAWellFormedAnalysis() {
		assertThatNoException().isThrownBy(() -> result("요약", "가설", AnalysisConfidence.LOW, List.of("근거"),
				List.of("조치"), List.of("한계")));
	}

	/** 목록은 밖에서 바꿀 수 없어야 한다. 검증을 통과한 값과 저장되는 값이 달라지면 검증의 뜻이 없다. */
	@Test
	void copiesTheListsItWasGiven() {
		List<String> evidence = new ArrayList<>(List.of("근거"));

		IncidentAnalysisResult result = result("요약", "가설", AnalysisConfidence.HIGH, evidence, List.of("조치"),
				List.of("한계"));
		evidence.add("나중에 끼워 넣은 근거");

		assertThat(result.evidence()).containsExactly("근거");
	}

	@Test
	void rejectsABlankSummary() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> result("   ", "가설", AnalysisConfidence.LOW, List.of("근거"), List.of("조치"),
					List.of("한계")));
	}

	@Test
	void rejectsAMissingConfidence() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> result("요약", "가설", null, List.of("근거"), List.of("조치"), List.of("한계")));
	}

	/**
	 * 한계가 없는 분석은 존재할 수 없다. 이 분석에는 log도 trace도 소스 코드도 없으므로, 그 자리가 비어
	 * 있다면 모델이 계약을 따르지 않은 것이다.
	 */
	@Test
	void rejectsEmptyLimitations() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> result("요약", "가설", AnalysisConfidence.LOW, List.of("근거"), List.of("조치"),
					List.of()));
	}

	@Test
	void rejectsEmptyEvidence() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> result("요약", "가설", AnalysisConfidence.LOW, List.of(), List.of("조치"),
					List.of("한계")));
	}

	@Test
	void rejectsEmptyRecommendedActions() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> result("요약", "가설", AnalysisConfidence.LOW, List.of("근거"), List.of(),
					List.of("한계")));
	}

	/** 목록이 길어지면 사람이 읽고 고르는 목록이 아니게 된다. 잘라 담지 않고 거절한다. */
	@Test
	void rejectsATooLongList() {
		List<String> tooMany = IntStream.rangeClosed(0, IncidentAnalysisResult.LIST_MAX_SIZE)
			.mapToObj((index) -> "근거 " + index)
			.toList();

		assertThatIllegalArgumentException()
			.isThrownBy(() -> result("요약", "가설", AnalysisConfidence.LOW, tooMany, List.of("조치"), List.of("한계")));
	}

	/** 상한을 넘긴 문장은 잘라서 담지 않는다. 잘라 담으면 중간에서 끊긴 분석이 정상 결과로 남는다. */
	@Test
	void rejectsAnOverlongSummary() {
		String tooLong = repeat(IncidentAnalysisResult.SUMMARY_MAX_LENGTH + 1);

		assertThatIllegalArgumentException()
			.isThrownBy(() -> result(tooLong, "가설", AnalysisConfidence.LOW, List.of("근거"), List.of("조치"),
					List.of("한계")));
	}

	@Test
	void rejectsAnOverlongListItem() {
		String tooLong = repeat(IncidentAnalysisResult.LIST_ITEM_MAX_LENGTH + 1);

		assertThatIllegalArgumentException()
			.isThrownBy(() -> result("요약", "가설", AnalysisConfidence.LOW, List.of(tooLong), List.of("조치"),
					List.of("한계")));
	}

	/** 어떤 model이 만들었는지 모르는 분석은 나중에 무엇도 설명하지 못한다. */
	@Test
	void rejectsABlankModel() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new IncidentAnalysisResult("요약", "가설", AnalysisConfidence.LOW, List.of("근거"),
					List.of("조치"), List.of("한계"), " ", IncidentAnalysisPrompt.VERSION, SNAPSHOT));
	}

	@Test
	void rejectsABlankPromptVersion() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new IncidentAnalysisResult("요약", "가설", AnalysisConfidence.LOW, List.of("근거"),
					List.of("조치"), List.of("한계"), MODEL, "", SNAPSHOT));
	}

	@Test
	void rejectsABlankInputSnapshot() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new IncidentAnalysisResult("요약", "가설", AnalysisConfidence.LOW, List.of("근거"),
					List.of("조치"), List.of("한계"), MODEL, IncidentAnalysisPrompt.VERSION, ""));
	}

	private static IncidentAnalysisResult result(String summary, String primaryHypothesis,
			AnalysisConfidence confidence, List<String> evidence, List<String> recommendedActions,
			List<String> limitations) {
		return new IncidentAnalysisResult(summary, primaryHypothesis, confidence, evidence, recommendedActions,
				limitations, MODEL, IncidentAnalysisPrompt.VERSION, SNAPSHOT);
	}

	private static String repeat(int length) {
		return String.join("", Collections.nCopies(length, "가"));
	}

}

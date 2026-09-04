package com.pulseops.analysis;

import java.util.List;

/**
 * 검증을 통과한 AI 분석 하나이다. 저장할 수 있는 형태이며, 이 record를 만들 수 있었다는 것이 곧
 * 응답이 계약을 지켰다는 뜻이다.
 * <p>
 * 검증을 생성자에 둔다. Provider가 돌려준 JSON을 그대로 Entity에 옮기면, 계약을 지켰는지 확인하는
 * 자리가 저장 직전 어딘가로 흩어지고 한 경로만 빠뜨려도 깨진 분석이 영구 기록이 된다. 여기를 지나야만
 * 저장할 수 있게 해 두면 확인하는 자리가 하나로 정해진다.
 * <p>
 * 상한을 두는 이유는 두 가지이다. 하나는 이 분석이 사람이 몇 초 안에 읽고 다음 행동을 정하기 위한
 * 것이라 길어질수록 쓸모가 줄기 때문이고, 다른 하나는 저장할 열의 길이가 정해져 있기 때문이다. 상한을
 * 넘긴 응답은 잘라 담지 않고 거절한다. 잘라 담으면 문장이 중간에서 끊긴 분석이 정상 결과로 남는다.
 * <p>
 * 세 목록 모두 비어 있을 수 없다. limitations가 특히 그렇다. 지금 이 분석에는 log도 trace도 소스 코드도
 * 없으므로 한계가 없는 분석은 존재할 수 없고, 그 자리가 비어 있다면 모델이 계약을 따르지 않은 것이다.
 * <p>
 * model과 promptVersion, inputSnapshot을 결과와 같은 자리에 담는다. 이 셋이 없으면 나중에 이 분석을
 * 다시 읽을 때 무엇이 무엇을 보고 만들었는지 말할 수 없다. 셋 다 이 분석을 만든 쪽만 아는 값이라
 * 결과와 함께 돌아와야 한다.
 */
public record IncidentAnalysisResult(String summary, String primaryHypothesis, AnalysisConfidence confidence,
		List<String> evidence, List<String> recommendedActions, List<String> limitations, String model,
		String promptVersion, String inputSnapshot) {

	public static final int SUMMARY_MAX_LENGTH = 2000;

	public static final int PRIMARY_HYPOTHESIS_MAX_LENGTH = 1000;

	/** 목록 하나에 담기는 최대 항목 수이다. 이보다 길어지면 사람이 읽고 고르는 목록이 아니게 된다. */
	public static final int LIST_MAX_SIZE = 5;

	public static final int LIST_ITEM_MAX_LENGTH = 400;

	private static final int MODEL_MAX_LENGTH = 100;

	private static final int PROMPT_VERSION_MAX_LENGTH = 50;

	private static final int INPUT_SNAPSHOT_MAX_LENGTH = 2000;

	public IncidentAnalysisResult {
		summary = requireBounded(summary, "summary", SUMMARY_MAX_LENGTH);
		primaryHypothesis = requireBounded(primaryHypothesis, "primaryHypothesis", PRIMARY_HYPOTHESIS_MAX_LENGTH);
		if (confidence == null) {
			throw new IllegalArgumentException("confidence가 없다");
		}
		evidence = requireBoundedList(evidence, "evidence");
		recommendedActions = requireBoundedList(recommendedActions, "recommendedActions");
		limitations = requireBoundedList(limitations, "limitations");
		model = requireBounded(model, "model", MODEL_MAX_LENGTH);
		promptVersion = requireBounded(promptVersion, "promptVersion", PROMPT_VERSION_MAX_LENGTH);
		inputSnapshot = requireBounded(inputSnapshot, "inputSnapshot", INPUT_SNAPSHOT_MAX_LENGTH);
	}

	/**
	 * 공백만 있는 값은 없는 값과 같게 본다. 빈 문자열을 통과시키면 summary가 비어 있는 분석이 성공한
	 * 분석으로 저장되고, 화면에는 제목만 있고 내용이 없는 칸이 남는다.
	 */
	private static String requireBounded(String value, String field, int maxLength) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + "가 비어 있다");
		}
		if (value.length() > maxLength) {
			throw new IllegalArgumentException(field + "가 상한을 넘었다: length=" + value.length() + " max=" + maxLength);
		}
		return value;
	}

	/**
	 * 목록을 복사해 들고 있는다. 부르는 쪽이 넘긴 목록을 그대로 참조하면 이 record를 만든 뒤에도 밖에서
	 * 내용을 바꿀 수 있어, 검증을 통과한 값과 저장되는 값이 달라질 수 있다.
	 */
	private static List<String> requireBoundedList(List<String> values, String field) {
		if (values == null || values.isEmpty()) {
			throw new IllegalArgumentException(field + "가 비어 있다");
		}
		if (values.size() > LIST_MAX_SIZE) {
			throw new IllegalArgumentException(field + "의 항목이 너무 많다: size=" + values.size() + " max=" + LIST_MAX_SIZE);
		}
		values.forEach((value) -> requireBounded(value, field + " 항목", LIST_ITEM_MAX_LENGTH));
		return List.copyOf(values);
	}

}

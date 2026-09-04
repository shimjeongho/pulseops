package com.pulseops.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Anthropic Messages API가 돌려주는 응답 본문 중 이 기능이 쓰는 부분이다.
 * <p>
 * 모르는 field를 무시한다. 이 응답의 모양은 외부 API가 정하고 우리가 쓰지 않는 값이 함께 오며, 그쪽이
 * field를 하나 더 붙였다는 이유로 분석이 실패하면 안 된다. 반대로 분석 본문 자체는 우리가 요구한
 * 계약이므로 그쪽은 엄격하게 읽는다. 남의 봉투는 느슨하게, 우리가 요구한 내용물은 엄격하게 읽는다.
 * <p>
 * {@code usage}를 담지 않는다. 사용량은 지금 아무 판단에도 쓰이지 않으며, 담아 두면 저장하거나 보여
 * 줄 자리를 찾게 되고 그것은 이 기능이 하려는 일이 아니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaudeMessageResponse(String model, @JsonProperty("stop_reason") String stopReason,
		List<ContentBlock> content) {

	/** 응답이 끝까지 만들어졌다는 뜻이다. 길이 제한에 잘렸거나 거절된 응답은 다른 값을 갖는다. */
	public static final String STOP_REASON_END_TURN = "end_turn";

	/**
	 * 응답 본문의 조각 하나이다.
	 * <p>
	 * {@code type}을 함께 읽는다. 응답에는 분석 본문 말고 다른 종류의 조각이 함께 올 수 있어, 첫 조각을
	 * 무조건 본문으로 읽으면 model이나 설정이 바뀌는 순간 엉뚱한 것을 분석으로 저장하게 된다.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ContentBlock(String type, String text) {

		public static final String TYPE_TEXT = "text";

	}

}

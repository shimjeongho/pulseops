package com.pulseops.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Anthropic Messages API에 보내는 요청 본문이다.
 * <p>
 * PulseOps의 REST DTO와 같은 Class를 쓰지 않는다. 이 모양은 우리가 정한 것이 아니라 외부 API가 정한
 * 것이고, 그쪽 계약이 바뀌면 여기가 따라 바뀌어야 한다. 우리 API 응답과 한 Class를 공유하면 남의
 * 계약 변경이 우리 Client에게 그대로 전달된다.
 * <p>
 * 필드 이름이 wire 형식과 달라 {@code @JsonProperty}로 못 박는다. Application 전체의 이름 전략을 바꿔
 * 맞추면 우리 API 응답의 모양까지 함께 바뀐다.
 * <p>
 * {@code thinking}이나 {@code temperature}를 보내지 않는다. model을 설정으로 받는데 그 값들은 model마다
 * 허용 여부가 달라, 어떤 model에서는 400으로 거절된다. 보내지 않으면 어느 model에서도 그 model의
 * 기본값으로 동작한다. 분석의 성격을 조절할 필요가 생기기 전까지 요청은 최소한만 담는다.
 */
public record ClaudeMessageRequest(String model, @JsonProperty("max_tokens") int maxTokens, String system,
		List<Message> messages, @JsonProperty("output_config") OutputConfig outputConfig) {

	public record Message(String role, String content) {
	}

	public record OutputConfig(Format format) {
	}

	/**
	 * 응답을 JSON Schema로 제약한다. 형식을 문장으로만 부탁하면 code fence가 붙거나 설명이 앞에 붙은
	 * 응답이 오고, 그것을 걷어내는 보정 parser는 새 변형이 나올 때마다 자란다.
	 */
	public record Format(String type, JsonNode schema) {

		/** Messages API가 JSON Schema 제약을 가리키는 이름이다. */
		public static final String JSON_SCHEMA = "json_schema";

	}

}

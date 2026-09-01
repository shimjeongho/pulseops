package com.pulseops.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 오류 응답 본문의 JSON 형태를 고정한다.
 * <p>
 * 이 형태는 Frontend가 실패를 다루는 유일한 근거이므로 field 이름 하나가 바뀌거나 늘어나는 것이
 * 곧 Client를 깨뜨리는 변경이다. Java record 선언만으로는 그 사실이 드러나지 않아 여기서 직렬화
 * 결과 자체를 확인한다.
 * <p>
 * 직접 만든 ObjectMapper가 아니라 Spring이 구성한 것을 주입받는다. 실제 응답을 쓰는 것은 그쪽이며,
 * 여기서 따로 만들면 이 테스트만 통과하고 실제 응답은 다른 형태로 나가는 일이 생긴다.
 */
@JsonTest
class ErrorResponseContractTest {

	private static final List<String> CONTRACT_FIELDS = List.of("timestamp", "status", "code", "message", "path");

	private static final Instant TIMESTAMP = Instant.parse("2026-09-01T06:00:00Z");

	private static final String PATH = "/api/example";

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void serializesExactlyTheFiveContractFields() {
		JsonNode body = serialize(new ErrorResponse(TIMESTAMP, 500, "INTERNAL_ERROR", "오류가 발생했습니다.", PATH));

		assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(CONTRACT_FIELDS);
		assertThat(body.size()).isEqualTo(CONTRACT_FIELDS.size());
	}

	/**
	 * 시각이 숫자로 나가면 Client가 단위를 스스로 정해야 하고, 시간대를 붙이지 않으면 같은 문자열이
	 * 환경마다 다른 순간을 뜻한다. UTC 기준 ISO-8601 문자열은 그 두 가지가 모두 없는 표기이다.
	 */
	@Test
	void writesTimestampAsIsoInstantInUtc() {
		JsonNode body = serialize(new ErrorResponse(TIMESTAMP, 500, "INTERNAL_ERROR", "오류가 발생했습니다.", PATH));

		assertThat(body.get("timestamp").isNumber()).isFalse();
		assertThat(body.get("timestamp").stringValue()).isEqualTo("2026-09-01T06:00:00Z");
	}

	/**
	 * status는 HTTP 상태 코드와 같은 숫자이다. 문자열로 나가면 Client가 비교 전에 변환해야 한다.
	 */
	@Test
	void writesStatusAsNumberAndTextFieldsAsGiven() {
		JsonNode body = serialize(new ErrorResponse(TIMESTAMP, 404, "INCIDENT_NOT_FOUND", "찾을 수 없습니다.", PATH));

		assertThat(body.get("status").isNumber()).isTrue();
		assertThat(body.get("status").intValue()).isEqualTo(404);
		assertThat(body.get("code").stringValue()).isEqualTo("INCIDENT_NOT_FOUND");
		assertThat(body.get("message").stringValue()).isEqualTo("찾을 수 없습니다.");
		assertThat(body.get("path").stringValue()).isEqualTo(PATH);
	}

	/** timestamp는 부르는 쪽이 정하지 않는다. 오류마다 시각을 넘기게 하면 빠뜨린 자리가 생긴다. */
	@Test
	void ofStampsTheResponseWithACreationTime() {
		Instant before = Instant.now();

		ErrorResponse response = ErrorResponse.of(500, "INTERNAL_ERROR", "오류가 발생했습니다.", PATH);

		assertThat(response.timestamp()).isBetween(before, Instant.now());
		assertThat(response.status()).isEqualTo(500);
		assertThat(response.code()).isEqualTo("INTERNAL_ERROR");
		assertThat(response.message()).isEqualTo("오류가 발생했습니다.");
		assertThat(response.path()).isEqualTo(PATH);
	}

	private JsonNode serialize(ErrorResponse response) {
		return this.objectMapper.readTree(this.objectMapper.writeValueAsString(response));
	}

}

package com.pulseops.demoshop.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Telemetry Event v1의 JSON 계약을 고정한다. Demo Shop과 PulseOps는 Java Model을 공유하지 않고
 * 이 JSON 형태로만 연결되므로, 계약이 바뀌면 Consumer가 아니라 여기서 먼저 깨져야 한다.
 * <p>
 * 비교는 JsonNode 단위로 수행하여 property 순서와 공백 차이에 의존하지 않는다.
 */
@JsonTest
class TelemetryEventContractTest {

	private static final String FIXTURE_PATH = "telemetry/telemetry-event-v1.json";

	private static final List<String> V1_FIELDS = List.of("eventId", "schemaVersion", "occurredAt", "service",
			"serviceInstance", "requestId", "method", "endpoint", "httpStatus", "success", "latencyMs", "errorType",
			"errorMessage");

	/**
	 * Telemetry에 실리면 안 되는 이름이다. v1에서 의도적으로 제외한 필드와 민감정보 필드를 함께
	 * 막는다. 대소문자를 무시하고 부분 일치까지 확인하므로 {@code authHeaders} 같은 변형도 걸린다.
	 */
	private static final List<String> FORBIDDEN_FIELDS = List.of("body", "requestBody", "headers", "authorization",
			"cookie", "password", "token", "stackTrace", "traceId", "metadata", "environment");

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void serializesExactlyTheThirteenV1Fields() {
		JsonNode json = serialize(businessErrorEvent());

		assertThat(json.propertyNames()).containsExactlyInAnyOrderElementsOf(V1_FIELDS);
		assertThat(json.size()).isEqualTo(13);
	}

	@Test
	void usesTheJsonTypesRequiredByTheContract() {
		JsonNode json = serialize(businessErrorEvent());

		assertThat(json.get("schemaVersion").isNumber()).isTrue();
		assertThat(json.get("httpStatus").isNumber()).isTrue();
		assertThat(json.get("latencyMs").isNumber()).isTrue();
		assertThat(json.get("success").isBoolean()).isTrue();
		assertThat(json.get("occurredAt").isString()).isTrue();
		assertThat(json.get("eventId").isString()).isTrue();
		assertThat(json.get("endpoint").isString()).isTrue();
	}

	@Test
	void writesOccurredAtAsIso8601InUtc() {
		Instant occurredAt = Instant.parse("2026-08-31T12:34:56.123Z");

		String serialized = serialize(businessErrorEvent()).get("occurredAt").asString();

		assertThat(serialized).isEqualTo("2026-08-31T12:34:56.123Z").endsWith("Z");
		assertThat(Instant.parse(serialized)).isEqualTo(occurredAt);
	}

	@Test
	void serializesBusinessErrorEvent() {
		JsonNode json = serialize(businessErrorEvent());

		assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
		assertThat(json.get("service").asString()).isEqualTo("demo-shop");
		assertThat(json.get("method").asString()).isEqualTo("POST");
		assertThat(json.get("endpoint").asString()).isEqualTo("/api/payments");
		assertThat(json.get("httpStatus").asInt()).isEqualTo(409);
		assertThat(json.get("success").asBoolean()).isFalse();
		assertThat(json.get("latencyMs").asLong()).isEqualTo(37L);
		assertThat(json.get("errorType").asString()).isEqualTo("ORDER_ALREADY_PAID");
		assertThat(json.get("errorMessage").asString()).isEqualTo("이미 결제가 완료된 주문입니다.");
	}

	@Test
	void serializesSuccessEventWithErrorFieldsPresentAndNull() {
		JsonNode json = serialize(successEvent());

		assertThat(json.get("httpStatus").asInt()).isEqualTo(200);
		assertThat(json.get("success").asBoolean()).isTrue();
		assertThat(json.get("endpoint").asString()).isEqualTo("/api/products");
		// null이어도 필드를 생략하지 않는다. 정상 Event와 오류 Event의 필드 수가 항상 같아야
		// Consumer가 결측과 null을 구분하지 않고 동일하게 다룰 수 있다.
		assertThat(json.get("errorType").isNull()).isTrue();
		assertThat(json.get("errorMessage").isNull()).isTrue();
		assertThat(json.size()).isEqualTo(13);
	}

	@Test
	void matchesCanonicalFixture() throws IOException {
		assertThat(serialize(businessErrorEvent())).isEqualTo(readCanonicalFixture());
	}

	@Test
	void neverExposesSensitiveFields() {
		List<String> serializedFieldNames = serialize(businessErrorEvent()).propertyNames()
			.stream()
			.map((name) -> name.toLowerCase(Locale.ROOT))
			.toList();

		for (String forbidden : FORBIDDEN_FIELDS) {
			assertThat(serializedFieldNames).noneMatch((name) -> name.contains(forbidden.toLowerCase(Locale.ROOT)));
		}
	}

	private JsonNode serialize(TelemetryEvent event) {
		return this.objectMapper.readTree(this.objectMapper.writeValueAsString(event));
	}

	private JsonNode readCanonicalFixture() throws IOException {
		try (InputStream fixture = new ClassPathResource(FIXTURE_PATH).getInputStream()) {
			return this.objectMapper.readTree(new String(fixture.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	private TelemetryEvent businessErrorEvent() {
		return new TelemetryEvent("3f1c8a27-6b90-4d1e-9c44-72b0d5e19a2b", 1,
				Instant.parse("2026-08-31T12:34:56.123Z"), "demo-shop", "demo-shop-01:8080",
				"a7d2f0c1-3e55-4a8b-b0d6-1f9c2ab84e10", "POST", "/api/payments", 409, false, 37L,
				"ORDER_ALREADY_PAID", "이미 결제가 완료된 주문입니다.");
	}

	private TelemetryEvent successEvent() {
		return new TelemetryEvent("11111111-2222-3333-4444-555555555555", 1,
				Instant.parse("2026-08-31T12:00:00.000Z"), "demo-shop", "demo-shop-01:8080",
				"99999999-8888-7777-6666-555555555555", "GET", "/api/products", 200, true, 12L, null, null);
	}

}

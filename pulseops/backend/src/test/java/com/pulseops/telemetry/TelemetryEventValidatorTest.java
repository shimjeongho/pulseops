package com.pulseops.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Telemetry Event v1 계약 검증 규칙을 고정한다. Producer가 계약을 깨면 Consumer는 값을 고치지
 * 않고 걸러 내므로, 어떤 Event를 통과시키고 어떤 Event를 버리는지가 곧 외부 계약이다.
 */
class TelemetryEventValidatorTest {

	private static final List<String> V1_FIELDS = List.of("eventId", "schemaVersion", "occurredAt", "service",
			"serviceInstance", "requestId", "method", "endpoint", "httpStatus", "success", "latencyMs", "errorType",
			"errorMessage");

	private static final List<String> REQUIRED_TEXT_FIELDS = List.of("eventId", "service", "serviceInstance",
			"requestId", "method", "endpoint");

	private final TelemetryEventValidator validator = new TelemetryEventValidator();

	// --- 값 검증 ------------------------------------------------------------

	@Test
	void acceptsBusinessErrorEvent() {
		assertThat(this.validator.validate(businessErrorEvent())).isNull();
	}

	/** 정상 Event는 errorType과 errorMessage가 없다. Consumer가 이를 요구하면 안 된다. */
	@Test
	void acceptsSuccessEventWithoutErrorFields() {
		assertThat(this.validator.validate(successEvent())).isNull();
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 2, -1 })
	void rejectsUnsupportedSchemaVersion(int schemaVersion) {
		TelemetryEvent valid = businessErrorEvent();
		TelemetryEvent event = new TelemetryEvent(valid.eventId(), schemaVersion, valid.occurredAt(), valid.service(),
				valid.serviceInstance(), valid.requestId(), valid.method(), valid.endpoint(), valid.httpStatus(),
				valid.success(), valid.latencyMs(), valid.errorType(), valid.errorMessage());

		assertThat(this.validator.validate(event)).isNotNull().contains("schemaVersion");
	}

	@Test
	void rejectsNullOccurredAt() {
		TelemetryEvent valid = businessErrorEvent();
		TelemetryEvent event = new TelemetryEvent(valid.eventId(), valid.schemaVersion(), null, valid.service(),
				valid.serviceInstance(), valid.requestId(), valid.method(), valid.endpoint(), valid.httpStatus(),
				valid.success(), valid.latencyMs(), valid.errorType(), valid.errorMessage());

		assertThat(this.validator.validate(event)).isNotNull().contains("occurredAt");
	}

	@ParameterizedTest(name = "{0}=[{1}]")
	@MethodSource("blankRequiredTextFields")
	void rejectsBlankRequiredTextField(String field, String blankValue) {
		assertThat(this.validator.validate(withBlank(field, blankValue))).isNotNull().contains(field);
	}

	static Stream<Arguments> blankRequiredTextFields() {
		return REQUIRED_TEXT_FIELDS.stream()
			.flatMap((field) -> Stream.of(arguments(field, null), arguments(field, ""), arguments(field, "  ")));
	}

	@ParameterizedTest
	@ValueSource(ints = { 99, 600, 0 })
	void rejectsHttpStatusOutsideTheHttpRange(int httpStatus) {
		assertThat(this.validator.validate(withStatus(httpStatus, httpStatus < 400))).isNotNull()
			.contains("httpStatus");
	}

	@ParameterizedTest(name = "httpStatus={0} success={1}")
	@CsvSource({ "100,true", "399,true", "400,false", "599,false" })
	void acceptsHttpStatusAtTheRangeBoundaries(int httpStatus, boolean success) {
		assertThat(this.validator.validate(withStatus(httpStatus, success))).isNull();
	}

	@Test
	void rejectsNegativeLatency() {
		assertThat(this.validator.validate(withLatency(-1L))).isNotNull().contains("latencyMs");
	}

	@Test
	void acceptsZeroLatency() {
		assertThat(this.validator.validate(withLatency(0L))).isNull();
	}

	/**
	 * v1에서 success는 {@code httpStatus < 400}과 같은 뜻이다. 어긋난 Event는 Producer 계약 오류이며
	 * Consumer가 success를 다시 계산해 고치지 않는다.
	 */
	@ParameterizedTest(name = "httpStatus={0} success={1}")
	@CsvSource({ "200,false", "500,true", "404,true" })
	void rejectsSuccessInconsistentWithHttpStatus(int httpStatus, boolean success) {
		assertThat(this.validator.validate(withStatus(httpStatus, success))).isNotNull().contains("success");
	}

	// --- 구조 검증 ----------------------------------------------------------

	@Test
	void acceptsPayloadCarryingAllV1Fields() {
		assertThat(this.validator.validateStructure(v1Payload())).isNull();
	}

	/**
	 * primitive 필드는 누락돼도 역직렬화 후 0이나 false가 되어 명시적인 값과 구분되지 않는다.
	 * 값이 아니라 필드 존재로 잡아야 latencyMs 0이나 success false 같은 유효한 값과 섞이지 않는다.
	 */
	@ParameterizedTest
	@MethodSource("v1Fields")
	void rejectsPayloadMissingAnyV1Field(String missingField) {
		ObjectNode payload = v1Payload();
		payload.remove(missingField);

		assertThat(this.validator.validateStructure(payload)).isNotNull().contains(missingField);
	}

	static Stream<String> v1Fields() {
		return V1_FIELDS.stream();
	}

	/** null 값은 결측이 아니다. errorType과 errorMessage는 null인 채로 통과해야 한다. */
	@Test
	void acceptsPayloadWithNullErrorFields() {
		ObjectNode payload = v1Payload();
		payload.putNull("errorType").putNull("errorMessage");

		assertThat(this.validator.validateStructure(payload)).isNull();
	}

	@Test
	void acceptsPayloadWithUnknownExtraField() {
		ObjectNode payload = v1Payload();
		payload.put("newField", "value");

		assertThat(this.validator.validateStructure(payload)).isNull();
	}

	@Test
	void rejectsPayloadThatIsNotAJsonObject() {
		assertThat(this.validator.validateStructure(JsonNodeFactory.instance.arrayNode())).isNotNull();
	}

	// --- 헬퍼 ---------------------------------------------------------------

	private static TelemetryEvent businessErrorEvent() {
		return new TelemetryEvent("3f1c8a27-6b90-4d1e-9c44-72b0d5e19a2b", 1,
				Instant.parse("2026-08-31T12:34:56.123Z"), "demo-shop", "demo-shop-01:8080",
				"a7d2f0c1-3e55-4a8b-b0d6-1f9c2ab84e10", "POST", "/api/payments", 409, false, 37L,
				"ORDER_ALREADY_PAID", "이미 결제가 완료된 주문입니다.");
	}

	private static TelemetryEvent successEvent() {
		return new TelemetryEvent("11111111-2222-3333-4444-555555555555", 1,
				Instant.parse("2026-08-31T12:00:00.000Z"), "demo-shop", "demo-shop-01:8080",
				"99999999-8888-7777-6666-555555555555", "GET", "/api/products", 200, true, 12L, null, null);
	}

	private static TelemetryEvent withBlank(String field, String blankValue) {
		TelemetryEvent valid = businessErrorEvent();
		return new TelemetryEvent(blankIf("eventId", field, blankValue, valid.eventId()), valid.schemaVersion(),
				valid.occurredAt(), blankIf("service", field, blankValue, valid.service()),
				blankIf("serviceInstance", field, blankValue, valid.serviceInstance()),
				blankIf("requestId", field, blankValue, valid.requestId()),
				blankIf("method", field, blankValue, valid.method()),
				blankIf("endpoint", field, blankValue, valid.endpoint()), valid.httpStatus(), valid.success(),
				valid.latencyMs(), valid.errorType(), valid.errorMessage());
	}

	private static String blankIf(String field, String targetField, String blankValue, String value) {
		return field.equals(targetField) ? blankValue : value;
	}

	private static TelemetryEvent withStatus(int httpStatus, boolean success) {
		TelemetryEvent valid = businessErrorEvent();
		return new TelemetryEvent(valid.eventId(), valid.schemaVersion(), valid.occurredAt(), valid.service(),
				valid.serviceInstance(), valid.requestId(), valid.method(), valid.endpoint(), httpStatus, success,
				valid.latencyMs(), valid.errorType(), valid.errorMessage());
	}

	private static TelemetryEvent withLatency(long latencyMs) {
		TelemetryEvent valid = businessErrorEvent();
		return new TelemetryEvent(valid.eventId(), valid.schemaVersion(), valid.occurredAt(), valid.service(),
				valid.serviceInstance(), valid.requestId(), valid.method(), valid.endpoint(), valid.httpStatus(),
				valid.success(), latencyMs, valid.errorType(), valid.errorMessage());
	}

	/** 구조 검증만 확인하므로 값의 Type은 계약과 같게 두되 값 자체는 최소로 채운다. */
	private static ObjectNode v1Payload() {
		ObjectNode payload = JsonNodeFactory.instance.objectNode();
		payload.put("eventId", "3f1c8a27-6b90-4d1e-9c44-72b0d5e19a2b");
		payload.put("schemaVersion", 1);
		payload.put("occurredAt", "2026-08-31T12:34:56.123Z");
		payload.put("service", "demo-shop");
		payload.put("serviceInstance", "demo-shop-01:8080");
		payload.put("requestId", "a7d2f0c1-3e55-4a8b-b0d6-1f9c2ab84e10");
		payload.put("method", "POST");
		payload.put("endpoint", "/api/payments");
		payload.put("httpStatus", 409);
		payload.put("success", false);
		payload.put("latencyMs", 37L);
		payload.put("errorType", "ORDER_ALREADY_PAID");
		payload.put("errorMessage", "이미 결제가 완료된 주문입니다.");
		return payload;
	}

}

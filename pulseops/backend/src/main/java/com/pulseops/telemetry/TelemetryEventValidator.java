package com.pulseops.telemetry;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 수신한 Event가 Telemetry Event v1 계약을 지키는지 확인한다.
 * <p>
 * Kafka에 의존하지 않는 순수 Java 객체로 두어 계약 검증만 단위 테스트로 고정한다. 검증 결과는
 * 위반 사유 문자열이며 계약을 지켰으면 null이다. WARN log에 "어떤 규칙이 깨졌는지"만 남기면
 * 충분하므로 별도의 결과 Type 계층을 만들지 않는다.
 * <p>
 * 사유 문자열에는 규칙과 숫자 값만 담고 문자열 필드의 실제 값은 담지 않는다. Event가 확장되더라도
 * 검증 실패 log가 값 노출 경로가 되지 않게 하기 위한 것이다.
 */
@Component
public class TelemetryEventValidator {

	/**
	 * Phase 2-2에서 확정한 v1 필드이다. errorType과 errorMessage는 값이 null일 수 있지만 필드
	 * 자체는 항상 존재하므로 결측과 명시적 null을 구분할 수 있다.
	 */
	private static final List<String> REQUIRED_FIELDS = List.of("eventId", "schemaVersion", "occurredAt", "service",
			"serviceInstance", "requestId", "method", "endpoint", "httpStatus", "success", "latencyMs", "errorType",
			"errorMessage");

	private static final int SUPPORTED_SCHEMA_VERSION = 1;

	private static final int MIN_HTTP_STATUS = 100;

	private static final int MAX_HTTP_STATUS = 599;

	/** success의 v1 의미를 고정하는 경계이다. */
	private static final int FIRST_FAILURE_STATUS = 400;

	/**
	 * 역직렬화 이전에 JSON 구조를 확인한다.
	 * <p>
	 * schemaVersion, httpStatus, latencyMs, success는 primitive이므로 필드가 빠져도 역직렬화 후에는
	 * 0이나 false로 채워져 명시적인 값과 구분되지 않는다. latencyMs 0과 success false는 그 자체로
	 * 유효한 값이라 값 검증만으로는 결측을 잡을 수 없다. 그래서 값이 아니라 필드 존재 여부를
	 * 먼저 본다.
	 * <p>
	 * 미래에 optional 필드가 추가되어도 v1 Consumer가 멈추지 않아야 하므로 계약에 없는 필드는
	 * 허용한다. 즉 "13개가 모두 있는가"만 확인하고 "13개뿐인가"는 확인하지 않는다.
	 */
	public String validateStructure(JsonNode payload) {
		if (!payload.isObject()) {
			return "payload가 JSON Object가 아니다";
		}
		for (String field : REQUIRED_FIELDS) {
			if (!payload.has(field)) {
				return "필수 필드 누락: " + field;
			}
		}
		return null;
	}

	/**
	 * 역직렬화된 Event의 값을 확인한다.
	 * <p>
	 * Producer가 Demo Shop 하나뿐이고 Phase 2-6에서 계약 Integration Test가 추가되므로 UUID 형식이나
	 * HTTP Method 목록처럼 계약이 정하지 않은 것까지 확인하지 않는다.
	 */
	public String validate(TelemetryEvent event) {
		if (event.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
			return "지원하지 않는 schemaVersion: " + event.schemaVersion();
		}
		if (event.occurredAt() == null) {
			return "occurredAt이 null이다";
		}
		String blankField = firstBlankField(event);
		if (blankField != null) {
			return "빈 값: " + blankField;
		}
		if (event.httpStatus() < MIN_HTTP_STATUS || event.httpStatus() > MAX_HTTP_STATUS) {
			return "httpStatus 범위 밖: " + event.httpStatus();
		}
		if (event.latencyMs() < 0) {
			return "latencyMs 음수: " + event.latencyMs();
		}
		// success는 v1에서 httpStatus < 400과 같은 뜻으로 고정됐다. 두 값이 어긋나면 Producer가
		// 계약을 깬 것이므로, Consumer가 값을 다시 계산해 고치지 않고 그대로 걸러 낸다.
		if (event.success() != (event.httpStatus() < FIRST_FAILURE_STATUS)) {
			return "success가 httpStatus와 어긋난다: httpStatus=" + event.httpStatus() + " success=" + event.success();
		}
		return null;
	}

	private String firstBlankField(TelemetryEvent event) {
		if (isBlank(event.eventId())) {
			return "eventId";
		}
		if (isBlank(event.service())) {
			return "service";
		}
		if (isBlank(event.serviceInstance())) {
			return "serviceInstance";
		}
		if (isBlank(event.requestId())) {
			return "requestId";
		}
		if (isBlank(event.method())) {
			return "method";
		}
		if (isBlank(event.endpoint())) {
			return "endpoint";
		}
		return null;
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}

package com.pulseops.telemetry;

import java.time.Instant;

/**
 * Demo Shop이 Kafka로 발행한 API 실행 관측 결과이다.
 * <p>
 * Demo Shop과 PulseOps의 계약은 Java Class가 아니라 JSON이므로 Demo Shop의 Model을 참조하지 않고
 * Consumer 쪽에서 같은 13개 필드를 따로 정의한다. 두 Application이 Class를 공유하면 한쪽의 내부
 * 변경이 그대로 다른 쪽 배포를 강제하게 되고, Kafka로 나눠 둔 경계가 사라진다.
 * <p>
 * {@code endpoint}는 실제 URI가 아닌 Route Template이고, {@code success}는 {@code httpStatus < 400}
 * 이라는 HTTP 수준의 사실만 뜻한다. {@code errorType}과 {@code errorMessage}는 null일 수 있으나
 * JSON 필드 자체는 v1 계약상 항상 존재한다.
 */
public record TelemetryEvent(String eventId, int schemaVersion, Instant occurredAt, String service,
		String serviceInstance, String requestId, String method, String endpoint, int httpStatus, boolean success,
		long latencyMs, String errorType, String errorMessage) {

}

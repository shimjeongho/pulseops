package com.pulseops.demoshop.telemetry;

import java.time.Instant;

/**
 * PulseOps로 전달하는 API 실행 관측 결과이다. Business 데이터가 아니라 하나의 HTTP Request가
 * 어떻게 끝났는지를 기술하며, 다음 제약을 계약으로 갖는다.
 * <p>
 * {@code endpoint}는 실제 URI가 아닌 Route Template이다. 실제 ID가 들어가면 Metric 기준이
 * 요청마다 달라져 집계가 무너지고 URI에 담긴 정보가 그대로 외부로 나간다.
 * <p>
 * {@code success}는 {@code httpStatus < 400}이라는 HTTP 수준의 사실만 뜻한다. 어떤 실패를
 * 장애로 볼지는 Metric 계층이 {@code httpStatus}와 {@code errorType}으로 따로 판단한다.
 * <p>
 * {@code errorType}은 Java Exception Class 이름이 아니라 Application이 정의한 Error Code를
 * 사용하고, {@code errorMessage}는 이미 Client에게 반환한 message만 담는다. Telemetry가 HTTP
 * 응답보다 더 많은 정보를 노출하지 않게 하기 위한 것이며, 따라서 Stack Trace와 원본 Exception
 * message는 담지 않는다.
 */
public record TelemetryEvent(String eventId, int schemaVersion, Instant occurredAt, String service,
		String serviceInstance, String requestId, String method, String endpoint, int httpStatus, boolean success,
		long latencyMs, String errorType, String errorMessage) {

}

package com.pulseops.demoshop.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 생성된 Telemetry Event를 PulseOps로 내보낸다. Kafka Producer 도입 이전 단계이므로 현재는
 * 관측 결과를 log로만 남기며, 이후 이 Method 내부만 Kafka 전송으로 교체한다.
 * <p>
 * 정상 요청까지 매 건 INFO로 남기면 Demo Shop log가 Telemetry로 뒤덮이므로 DEBUG를 사용한다.
 * Event의 endpoint는 Route Template이고 errorMessage는 이미 Client에게 반환한 값이므로 이
 * log에는 민감정보가 실리지 않는다.
 */
public class TelemetryPublisher {

	private static final Logger logger = LoggerFactory.getLogger(TelemetryPublisher.class);

	public void publish(TelemetryEvent event) {
		logger.debug("Telemetry Event eventId={} requestId={} {} {} httpStatus={} latencyMs={} errorType={}",
				event.eventId(), event.requestId(), event.method(), event.endpoint(), event.httpStatus(),
				event.latencyMs(), event.errorType());
	}

}

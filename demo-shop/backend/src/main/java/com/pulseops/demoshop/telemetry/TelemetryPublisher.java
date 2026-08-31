package com.pulseops.demoshop.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 생성된 Telemetry Event를 Kafka로 내보낸다.
 * <p>
 * Demo Shop과 PulseOps의 계약은 Java Class가 아니라 JSON 자체이므로, Spring이 관리하는
 * ObjectMapper로 직접 직렬화한 문자열을 보낸다. Java Type Header에 의존하는 Serializer를 쓰면
 * Consumer가 Demo Shop의 Class 구조에 묶인다.
 * <p>
 * 전송은 Broker ack를 기다리지 않는다. Business Request Thread가 Kafka 응답을 기다리면 Broker가
 * 느려질 때 Demo Shop API가 함께 느려지기 때문이다. 결과는 완료 Callback에서 log로만 남긴다.
 */
public class TelemetryPublisher {

	/** Phase 2에서 확정한 단일 Telemetry Topic이다. Topic 이름의 유일한 출처이다. */
	public static final String TOPIC = "demo-shop.telemetry.api.v1";

	private static final Logger logger = LoggerFactory.getLogger(TelemetryPublisher.class);

	private final KafkaTemplate<String, String> kafkaTemplate;

	private final ObjectMapper objectMapper;

	public TelemetryPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
		this.kafkaTemplate = kafkaTemplate;
		this.objectMapper = objectMapper;
	}

	/**
	 * 직렬화 실패는 Broker 장애가 아니라 관측 기능 자체의 결함이므로 전송 실패와 구분해 ERROR로
	 * 남기고 발행을 중단한다. 전송 호출 자체가 던지는 오류는 여기서 다시 잡지 않는다. 관측 실패가
	 * Business 응답을 바꾸지 않게 막는 책임은 TelemetryFilter 한 곳에 있다.
	 */
	public void publish(TelemetryEvent event) {
		String payload;
		try {
			payload = this.objectMapper.writeValueAsString(event);
		}
		catch (JacksonException ex) {
			logger.error("Telemetry Event를 JSON으로 변환하지 못했다. eventId={}", event.eventId(), ex);
			return;
		}
		this.kafkaTemplate.send(TOPIC, keyOf(event), payload)
			.whenComplete((result, failure) -> logDelivery(event, failure));
	}

	/**
	 * Key는 {@code {service}:{endpoint}}이다. 같은 endpoint의 Event가 같은 partition으로 모여야
	 * PulseOps가 endpoint 단위 Metric을 순서대로 집계할 수 있다. requestId나 eventId를 쓰면 Key가
	 * 매번 달라져 이 성질이 사라지고, 실제 URI를 쓰면 ID마다 Key가 갈라지며 URI에 담긴 값이 그대로
	 * 밖으로 나간다.
	 */
	private String keyOf(TelemetryEvent event) {
		return event.service() + ":" + event.endpoint();
	}

	/**
	 * Broker 전달 실패는 Business Request가 이미 끝난 뒤에 확인되므로 응답을 되돌릴 수 없다.
	 * 관측 유실 사실만 남기고, 향후 Event가 확장되더라도 민감정보가 새지 않도록 payload 전체는
	 * 남기지 않는다.
	 */
	private void logDelivery(TelemetryEvent event, Throwable failure) {
		if (failure != null) {
			logger.warn("Telemetry Event를 Kafka로 보내지 못했다. topic={} eventId={}", TOPIC, event.eventId(),
					failure);
			return;
		}
		logger.debug("Telemetry Event topic={} eventId={} requestId={} {} {} httpStatus={} latencyMs={} errorType={}",
				TOPIC, event.eventId(), event.requestId(), event.method(), event.endpoint(), event.httpStatus(),
				event.latencyMs(), event.errorType());
	}

}

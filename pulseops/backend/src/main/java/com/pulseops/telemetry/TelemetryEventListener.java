package com.pulseops.telemetry;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Demo Shop이 발행한 Telemetry Event를 수신해 계약을 확인한다.
 * <p>
 * Kafka value를 String으로 받아 Application 안에서 직접 역직렬화한다. Deserializer 계층에서
 * 역직렬화하면 잘못된 payload 하나가 Listener에 닿기도 전에 터져 Spring Kafka의 error handler와
 * Retry/DLT 구성을 강제하게 된다. 이 단계에서는 그런 장치 없이도 잘못된 Event를 log로 남기고
 * 넘어갈 수 있어야 한다.
 * <p>
 * 계약을 어긴 Event 때문에 예외를 밖으로 던지지 않는다. 같은 offset이 계속 재전달되어 Consumer가
 * 정체되는 구조를 만들지 않기 위한 것이며, 잘못된 Event는 현재 offset에서 처리 완료로 본다.
 * 반대로 예상하지 못한 오류는 삼키지 않고 그대로 올려 보내 관측 결함이 조용히 묻히지 않게 한다.
 * <p>
 * 계약을 지킨 Event는 {@link TelemetryProcessingService}에 넘기고 끝낸다. 집계에 넣을 Event인지,
 * 넣은 뒤에 무엇을 더 할지는 그쪽이 정한다. 이 Class가 협력자를 여럿 들고 순서를 정하기 시작하면
 * Kafka Record를 꺼내는 책임과 관측 결과를 다루는 책임이 한자리에 섞인다.
 */
@Component
public class TelemetryEventListener {

	/** Phase 2에서 확정한 단일 Telemetry Topic이다. Consumer group은 설정 파일에서 정한다. */
	static final String TOPIC = "demo-shop.telemetry.api.v1";

	private static final Logger logger = LoggerFactory.getLogger(TelemetryEventListener.class);

	private final ObjectMapper objectMapper;

	private final TelemetryEventValidator validator;

	private final TelemetryProcessingService processingService;

	public TelemetryEventListener(ObjectMapper objectMapper, TelemetryEventValidator validator,
			TelemetryProcessingService processingService) {
		this.objectMapper = objectMapper;
		this.validator = validator;
		this.processingService = processingService;
	}

	@KafkaListener(topics = TOPIC)
	public void consume(ConsumerRecord<String, String> record) {
		// Kafka는 value가 없는 Record도 전달할 수 있다. 역직렬화 이전에 걸러 내지 않으면 계약
		// 위반이 아니라 Listener 자체의 오류로 번진다.
		if (record.value() == null) {
			skip(record, "payload가 없다");
			return;
		}
		TelemetryEvent event;
		try {
			JsonNode payload = this.objectMapper.readTree(record.value());
			String structureViolation = this.validator.validateStructure(payload);
			if (structureViolation != null) {
				skip(record, structureViolation);
				return;
			}
			event = this.objectMapper.treeToValue(payload, TelemetryEvent.class);
		}
		catch (JacksonException ex) {
			skip(record, "JSON을 읽지 못했다: " + ex.getClass().getSimpleName());
			return;
		}
		String violation = this.validator.validate(event);
		if (violation != null) {
			skip(record, violation);
			return;
		}
		logger.debug("Telemetry Event topic={} partition={} offset={} eventId={} service={} endpoint={} httpStatus={} latencyMs={}",
				record.topic(), record.partition(), record.offset(), event.eventId(), event.service(),
				event.endpoint(), event.httpStatus(), event.latencyMs());
		this.processingService.process(event);
	}

	/**
	 * payload와 Jackson 예외 message는 남기지 않는다. Event에는 errorMessage처럼 Client에게 나간
	 * 문장이 들어 있고 Jackson 예외 message에는 문제가 된 값이 그대로 담기므로, 두 경로 모두
	 * 값 노출 통로가 된다. 대신 어떤 Record였는지 다시 찾을 수 있는 좌표만 남긴다.
	 */
	private void skip(ConsumerRecord<String, String> record, String reason) {
		logger.warn("Telemetry Event를 건너뛴다. topic={} partition={} offset={} key={} payloadLength={} reason={}",
				record.topic(), record.partition(), record.offset(), record.key(),
				(record.value() != null) ? record.value().length() : 0, reason);
	}

}

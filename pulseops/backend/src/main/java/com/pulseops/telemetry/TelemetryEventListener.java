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
 * Kafka value를 String으로 받아 Application 안에서 직접 역직렬화한다. 어떤 byte 배열이든 String으로는
 * 읽히므로 Deserializer 계층은 실패하지 않고, 잘못된 payload도 Listener 안에서 계약 위반으로 다룰 수
 * 있다. 그래서 역직렬화 실패를 따로 감싸는 ErrorHandlingDeserializer가 필요하지 않다.
 * <p>
 * 계약을 어긴 Event는 {@link TelemetryEventContractException}으로 올려 보낸다. Phase 6까지는 log만
 * 남기고 넘어갔는데, 그러면 잘못된 Event가 어디에도 남지 않아 나중에 무엇이 왜 버려졌는지 확인할 수
 * 없었다. 이제는 이 예외가 재시도 없이 DLT로 가므로, Consumer는 여전히 멈추지 않으면서 원본 Record는
 * 보존된다.
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
			throw contractViolation(record, "payload가 없다");
		}
		TelemetryEvent event;
		try {
			JsonNode payload = this.objectMapper.readTree(record.value());
			String structureViolation = this.validator.validateStructure(payload);
			if (structureViolation != null) {
				throw contractViolation(record, structureViolation);
			}
			event = this.objectMapper.treeToValue(payload, TelemetryEvent.class);
		}
		catch (JacksonException ex) {
			throw contractViolation(record, "JSON을 읽지 못했다: " + ex.getClass().getSimpleName());
		}
		String violation = this.validator.validate(event);
		if (violation != null) {
			throw contractViolation(record, violation);
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
	 * <p>
	 * 던지지 않고 돌려준다. 부르는 쪽에서 {@code throw}가 보여야 그 자리에서 처리가 끝난다는 것이
	 * 드러나고, compiler도 뒤 문장을 도달할 수 없는 코드로 다룬다.
	 */
	private TelemetryEventContractException contractViolation(ConsumerRecord<String, String> record, String reason) {
		logger.warn("계약을 어긴 Telemetry Event를 DLT로 보낸다. topic={} partition={} offset={} key={} payloadLength={} reason={}",
				record.topic(), record.partition(), record.offset(), record.key(),
				(record.value() != null) ? record.value().length() : 0, reason);
		return new TelemetryEventContractException(reason);
	}

}

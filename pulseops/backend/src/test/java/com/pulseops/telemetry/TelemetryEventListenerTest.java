package com.pulseops.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Listener가 Kafka Record 하나를 어떻게 처리하는지 Broker 없이 고정한다.
 * <p>
 * 핵심 계약은 "잘못된 Event 때문에 예외가 Listener 밖으로 나가지 않는다"이다. 예외가 나가면 같은
 * offset이 계속 재전달되어 Consumer가 그 자리에서 멈추기 때문이다. 건너뛴 사실은 WARN log로만
 * 남으므로 log 출력까지 함께 확인한다.
 * <p>
 * ObjectMapper는 Spring이 구성한 Bean을 그대로 쓴다. 테스트에서 따로 만든 ObjectMapper로 검증하면
 * 실제 Application의 역직렬화 동작과 달라질 수 있다.
 */
@JsonTest
@ExtendWith(OutputCaptureExtension.class)
class TelemetryEventListenerTest {

	private static final String FIXTURE_PATH = "telemetry/telemetry-event-v1.json";

	private static final String SKIP_LOG_MARKER = "Telemetry Event를 건너뛴다";

	private static final String RECORD_KEY = "demo-shop:/api/payments";

	private static final int PARTITION = 2;

	private static final long OFFSET = 41L;

	@Autowired
	private ObjectMapper objectMapper;

	private final TelemetryProcessingService processingService = mock(TelemetryProcessingService.class);

	private TelemetryEventListener listener;

	@BeforeEach
	void createListener() {
		this.listener = new TelemetryEventListener(this.objectMapper, new TelemetryEventValidator(),
				this.processingService);
	}

	@Test
	void acceptsCanonicalEvent(CapturedOutput output) throws IOException {
		consume(readCanonicalFixture());

		assertThat(output).doesNotContain(SKIP_LOG_MARKER);
	}

	@Test
	void skipsMalformedJson(CapturedOutput output) {
		assertThatCode(() -> consume("{not-json")).doesNotThrowAnyException();

		assertThat(output).contains(SKIP_LOG_MARKER).contains("offset=" + OFFSET);
	}

	@Test
	void skipsRecordWithoutPayload(CapturedOutput output) {
		assertThatCode(() -> consume(null)).doesNotThrowAnyException();

		assertThat(output).contains(SKIP_LOG_MARKER);
	}

	@Test
	void skipsUnsupportedSchemaVersion(CapturedOutput output) throws IOException {
		consume(canonicalPayloadWith((payload) -> payload.put("schemaVersion", 2)));

		assertThat(output).contains(SKIP_LOG_MARKER).contains("schemaVersion");
	}

	@Test
	void skipsEventWithBlankRequiredField(CapturedOutput output) throws IOException {
		consume(canonicalPayloadWith((payload) -> payload.put("endpoint", "  ")));

		assertThat(output).contains(SKIP_LOG_MARKER).contains("endpoint");
	}

	@Test
	void skipsEventWithNegativeLatency(CapturedOutput output) throws IOException {
		consume(canonicalPayloadWith((payload) -> payload.put("latencyMs", -1)));

		assertThat(output).contains(SKIP_LOG_MARKER).contains("latencyMs");
	}

	/**
	 * httpStatus가 빠지면 역직렬화 후 int 기본값 0이 된다. 값 범위 검증만으로도 걸리지만, 필드
	 * 존재 검증이 먼저 잡아야 결측과 실제 값 0을 구분한 채로 사유가 남는다.
	 */
	@Test
	void skipsPayloadMissingHttpStatus(CapturedOutput output) throws IOException {
		consume(canonicalPayloadWith((payload) -> payload.remove("httpStatus")));

		assertThat(output).contains(SKIP_LOG_MARKER).contains("필수 필드 누락: httpStatus");
	}

	/**
	 * latencyMs 0과 success false는 그 자체로 유효한 값이라 역직렬화 후에는 결측과 구분되지 않는다.
	 * 필드 존재 검증이 없으면 계약을 어긴 Event가 정상 Event로 통과한다.
	 */
	@Test
	void skipsPayloadMissingFieldsThatHaveValidPrimitiveDefaults(CapturedOutput output) throws IOException {
		consume(canonicalPayloadWith((payload) -> payload.remove("latencyMs")));
		consume(canonicalPayloadWith((payload) -> payload.remove("success")));
		consume(canonicalPayloadWith((payload) -> payload.remove("errorMessage")));

		assertThat(output).contains("필수 필드 누락: latencyMs")
			.contains("필수 필드 누락: success")
			.contains("필수 필드 누락: errorMessage");
	}

	@Test
	void skipsEventWithUnreadableOccurredAt(CapturedOutput output) throws IOException {
		assertThatCode(() -> consume(canonicalPayloadWith((payload) -> payload.put("occurredAt", "어제"))))
			.doesNotThrowAnyException();

		assertThat(output).contains(SKIP_LOG_MARKER);
	}

	@Test
	void acceptsEventCarryingAnUnknownFieldFromAFutureProducer(CapturedOutput output) throws IOException {
		consume(canonicalPayloadWith((payload) -> payload.put("newField", "value")));

		assertThat(output).doesNotContain(SKIP_LOG_MARKER);
	}

	/**
	 * Event에는 Client에게 나간 errorMessage가 들어 있고 Jackson 예외 message에는 문제가 된 값이
	 * 그대로 담긴다. 두 경로 모두 log로 새면 안 되므로 payload 원문 없이 좌표만 남는지 확인한다.
	 */
	@Test
	void neverWritesThePayloadIntoTheSkipLog(CapturedOutput output) throws IOException {
		String payload = canonicalPayloadWith((node) -> node.put("schemaVersion", 2));

		consume(payload);

		assertThat(output).contains(SKIP_LOG_MARKER)
			.doesNotContain("이미 결제가 완료된 주문입니다.")
			.doesNotContain("ORDER_ALREADY_PAID")
			.doesNotContain("3f1c8a27-6b90-4d1e-9c44-72b0d5e19a2b")
			.contains("payloadLength=" + payload.length());
	}

	/**
	 * 검증을 통과한 Event만 처리로 넘어간다. Listener가 Event를 두 번 넘기면 한 번 일어난 요청이 두 번
	 * 세어지고, Redis dedup은 같은 eventId를 한 번만 받으므로 그 중복을 걸러 주지 못한다.
	 * <p>
	 * 넘긴 다음에 무엇이 일어나는지는 여기서 보지 않는다. 집계에 넣을지, 넣은 뒤 이상 판정을 부를지는
	 * {@link TelemetryProcessingService}가 정하며, 그 순서를 Listener가 알면 Kafka Record를 꺼내는
	 * 책임과 관측 결과를 다루는 책임이 한자리에 섞인다.
	 */
	@Test
	void handsAValidEventToTheProcessingServiceOnce() throws IOException {
		consume(readCanonicalFixture());

		ArgumentCaptor<TelemetryEvent> processed = ArgumentCaptor.forClass(TelemetryEvent.class);
		then(this.processingService).should().process(processed.capture());
		assertThat(processed.getValue().eventId()).isEqualTo("3f1c8a27-6b90-4d1e-9c44-72b0d5e19a2b");
		assertThat(processed.getValue().httpStatus()).isEqualTo(409);
	}

	/**
	 * 계약을 어긴 Event가 처리에 닿으면 Metric이 실제로 일어나지 않은 요청까지 세게 된다. 집계 창이나
	 * 오류 판정 이전에, 계약 검증을 지난 Event만 넘어간다는 것이 이 경계의 계약이다.
	 */
	@Test
	void neverHandsAnInvalidEventToTheProcessingService() throws IOException {
		consume(null);
		consume("{not-json");
		consume(canonicalPayloadWith((payload) -> payload.put("schemaVersion", 2)));
		consume(canonicalPayloadWith((payload) -> payload.remove("httpStatus")));
		consume(canonicalPayloadWith((payload) -> payload.put("latencyMs", -1)));
		consume(canonicalPayloadWith((payload) -> payload.put("endpoint", "  ")));

		then(this.processingService).shouldHaveNoInteractions();
	}

	private void consume(String payload) {
		this.listener
			.consume(new ConsumerRecord<>(TelemetryEventListener.TOPIC, PARTITION, OFFSET, RECORD_KEY, payload));
	}

	/** canonical fixture에서 한 부분만 바꿔 payload를 만든다. 나머지 필드 구성은 계약 그대로 남는다. */
	private String canonicalPayloadWith(Consumer<ObjectNode> change) throws IOException {
		ObjectNode payload = (ObjectNode) this.objectMapper.readTree(readCanonicalFixture());
		change.accept(payload);
		return this.objectMapper.writeValueAsString(payload);
	}

	private String readCanonicalFixture() throws IOException {
		try (InputStream fixture = new ClassPathResource(FIXTURE_PATH).getInputStream()) {
			return new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}

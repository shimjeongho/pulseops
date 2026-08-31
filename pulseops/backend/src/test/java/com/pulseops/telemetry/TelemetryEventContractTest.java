package com.pulseops.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Telemetry Event v1 JSON을 PulseOps가 그대로 읽어 낼 수 있는지 고정한다.
 * <p>
 * Demo Shop과 PulseOps는 Java Model을 공유하지 않으므로 두 Application이 같은 canonical fixture를
 * 각자 검증한다. Demo Shop이 계약을 바꾸면 Producer 쪽뿐 아니라 여기서도 깨져야 한다.
 */
@JsonTest
class TelemetryEventContractTest {

	private static final String FIXTURE_PATH = "telemetry/telemetry-event-v1.json";

	private static final List<String> V1_FIELDS = List.of("eventId", "schemaVersion", "occurredAt", "service",
			"serviceInstance", "requestId", "method", "endpoint", "httpStatus", "success", "latencyMs", "errorType",
			"errorMessage");

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void canonicalFixtureCarriesExactlyTheThirteenV1Fields() throws IOException {
		JsonNode fixture = this.objectMapper.readTree(readCanonicalFixture());

		assertThat(fixture.propertyNames()).containsExactlyInAnyOrderElementsOf(V1_FIELDS);
		assertThat(fixture.size()).isEqualTo(13);
	}

	@Test
	void deserializesCanonicalFixtureIntoTheConsumerEvent() throws IOException {
		TelemetryEvent event = this.objectMapper.readValue(readCanonicalFixture(), TelemetryEvent.class);

		assertThat(event.eventId()).isEqualTo("3f1c8a27-6b90-4d1e-9c44-72b0d5e19a2b");
		assertThat(event.schemaVersion()).isEqualTo(1);
		assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-31T12:34:56.123Z"));
		assertThat(event.service()).isEqualTo("demo-shop");
		assertThat(event.serviceInstance()).isEqualTo("demo-shop-01:8080");
		assertThat(event.requestId()).isEqualTo("a7d2f0c1-3e55-4a8b-b0d6-1f9c2ab84e10");
		assertThat(event.method()).isEqualTo("POST");
		assertThat(event.endpoint()).isEqualTo("/api/payments");
		assertThat(event.httpStatus()).isEqualTo(409);
		assertThat(event.success()).isFalse();
		assertThat(event.latencyMs()).isEqualTo(37L);
		assertThat(event.errorType()).isEqualTo("ORDER_ALREADY_PAID");
		assertThat(event.errorMessage()).isEqualTo("이미 결제가 완료된 주문입니다.");
	}

	/**
	 * 다시 직렬화한 결과가 fixture와 같아야 Consumer Model이 Producer 계약과 어긋나지 않는다.
	 * 비교는 JsonNode 단위로 하여 property 순서와 공백 차이에 의존하지 않는다.
	 */
	@Test
	void reserializesIntoTheSameJson() throws IOException {
		TelemetryEvent event = this.objectMapper.readValue(readCanonicalFixture(), TelemetryEvent.class);

		JsonNode reserialized = this.objectMapper.readTree(this.objectMapper.writeValueAsString(event));

		assertThat(reserialized).isEqualTo(this.objectMapper.readTree(readCanonicalFixture()));
	}

	/**
	 * 정상 Event에서는 errorType과 errorMessage가 null이지만 JSON 필드 자체는 존재한다. 이 성질이
	 * 있어야 Consumer가 결측과 명시적 null을 구분할 수 있다.
	 */
	@Test
	void readsErrorFieldsThatArePresentButNull() throws IOException {
		ObjectNode payload = canonicalPayload();
		payload.put("httpStatus", 200).put("success", true).putNull("errorType").putNull("errorMessage");

		TelemetryEvent event = this.objectMapper.treeToValue(payload, TelemetryEvent.class);

		assertThat(payload.has("errorType")).isTrue();
		assertThat(event.errorType()).isNull();
		assertThat(event.errorMessage()).isNull();
		assertThat(event.success()).isTrue();
	}

	/**
	 * v1 Consumer는 나중에 optional 필드가 추가되어도 멈추면 안 된다. 전역 설정을 바꾸는 대신
	 * Spring이 구성한 ObjectMapper의 실제 동작을 확인한다.
	 */
	@Test
	void acceptsUnknownFieldAddedByAFutureProducer() throws IOException {
		ObjectNode payload = canonicalPayload();
		payload.put("newField", "value");

		TelemetryEvent event = this.objectMapper.treeToValue(payload, TelemetryEvent.class);

		assertThat(event.schemaVersion()).isEqualTo(1);
		assertThat(event.endpoint()).isEqualTo("/api/payments");
	}

	private ObjectNode canonicalPayload() throws IOException {
		return (ObjectNode) this.objectMapper.readTree(readCanonicalFixture());
	}

	private String readCanonicalFixture() throws IOException {
		try (InputStream fixture = new ClassPathResource(FIXTURE_PATH).getInputStream()) {
			return new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}

package com.pulseops.demoshop.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Telemetry Event를 Kafka로 내보내는 규칙을 검증한다. Event JSON의 필드 계약은
 * TelemetryEventContractTest가 고정하므로, 여기서는 Publisher 자신의 책임인 Topic, Key, 비동기
 * 전송, 실패 처리만 확인한다.
 */
class TelemetryPublisherTest {

	private static final String PAYLOAD = "{\"eventId\":\"serialized-by-object-mapper\"}";

	/** 사용 중일 가능성이 낮은 Port이다. Broker가 없는 상황을 만들기 위해 사용한다. */
	private static final String UNREACHABLE_BROKER = "localhost:59092";

	private static final int MAX_BLOCK_MS = 500;

	private final KafkaTemplate<String, String> kafkaTemplate = mockKafkaTemplate();

	private final ObjectMapper objectMapper = mock(ObjectMapper.class);

	private final TelemetryPublisher publisher = new TelemetryPublisher(this.kafkaTemplate, this.objectMapper);

	@Test
	void sendsTheSerializedEventToTheTelemetryTopic() {
		TelemetryEvent event = successEvent("/api/products/{productId}");
		givenSendSucceeds(event);

		this.publisher.publish(event);

		verify(this.kafkaTemplate).send(eq("demo-shop.telemetry.api.v1"),
				eq("demo-shop:/api/products/{productId}"), eq(PAYLOAD));
	}

	/**
	 * Key는 service와 Route Template만 사용한다. 같은 endpoint의 Event가 한 partition에 모여야
	 * PulseOps가 endpoint 단위로 순서 있게 집계할 수 있기 때문이다.
	 */
	@ParameterizedTest
	@CsvSource({ "/api/products, demo-shop:/api/products", "/api/orders/{orderId}, demo-shop:/api/orders/{orderId}",
			"/api/payments, demo-shop:/api/payments", "UNMAPPED, demo-shop:UNMAPPED" })
	void buildsTheKeyFromTheServiceAndTheRouteTemplate(String endpoint, String expectedKey) {
		TelemetryEvent event = successEvent(endpoint);
		givenSendSucceeds(event);

		this.publisher.publish(event);

		verify(this.kafkaTemplate).send(anyString(), eq(expectedKey), anyString());
	}

	/**
	 * 요청마다 달라지는 값이 Key에 들어가면 endpoint 단위로 모이는 성질이 사라진다.
	 */
	@Test
	void neverPutsRequestScopedValuesInTheKey() {
		TelemetryEvent event = successEvent("/api/orders/{orderId}");
		givenSendSucceeds(event);

		this.publisher.publish(event);

		assertThat(capturedKey()).doesNotContain(event.requestId()).doesNotContain(event.eventId());
	}

	/**
	 * 직렬화 실패는 관측 기능 자체의 결함이므로 Event를 보내지 않고 끝낸다. Business Request는
	 * 이미 끝난 뒤이므로 영향을 받지 않아야 한다.
	 */
	@Test
	void doesNotSendAnythingWhenSerializationFails() {
		TelemetryEvent event = successEvent("/api/products");
		when(this.objectMapper.writeValueAsString(event)).thenThrow(serializationFailure());

		assertThatCode(() -> this.publisher.publish(event)).doesNotThrowAnyException();

		verify(this.kafkaTemplate, never()).send(anyString(), anyString(), anyString());
	}

	/**
	 * Broker 전달 실패는 Business Request가 끝난 뒤 비동기로 확인되므로 호출부로 다시 전파되면
	 * 안 된다.
	 */
	@Test
	void doesNotPropagateAnAsynchronousDeliveryFailure() {
		TelemetryEvent event = successEvent("/api/products");
		when(this.objectMapper.writeValueAsString(event)).thenReturn(PAYLOAD);
		when(this.kafkaTemplate.send(anyString(), anyString(), anyString()))
			.thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Broker 전달 실패")));

		assertThatCode(() -> this.publisher.publish(event)).doesNotThrowAnyException();
	}

	/**
	 * 동기 전송 오류는 Publisher가 삼키지 않는다. 관측 실패가 Business 응답을 바꾸지 않게 막는
	 * 책임은 TelemetryFilter 한 곳에 있고, Publisher까지 같은 일을 하면 소유자가 둘이 된다.
	 */
	@Test
	void letsASynchronousSendFailureReachTheCaller() {
		TelemetryEvent event = successEvent("/api/products");
		when(this.objectMapper.writeValueAsString(event)).thenReturn(PAYLOAD);
		when(this.kafkaTemplate.send(anyString(), anyString(), anyString()))
			.thenThrow(new IllegalStateException("Producer 상태 오류"));

		assertThatThrownBy(() -> this.publisher.publish(event)).isInstanceOf(IllegalStateException.class);
	}

	/**
	 * Broker가 없으면 send는 metadata를 기다리다 실패한다. 중요한 것은 그 대기가 max.block.ms로
	 * 제한되어 Business Request Thread가 무한히 붙잡히지 않는다는 점이다. 발생한 오류는
	 * TelemetryFilter가 받아 Business 응답과 분리한다.
	 * <p>
	 * 시간 상한은 "무한히 붙잡히지 않는다"만 확인하면 되므로 설정값보다 훨씬 여유 있게 둔다.
	 * 실제 소요 시간을 단정하면 느린 환경에서 깨지는 테스트가 된다.
	 */
	@Test
	void failsFastInsteadOfBlockingWhenTheBrokerIsUnreachable() {
		ProducerFactory<String, String> producerFactory = new DefaultKafkaProducerFactory<>(producerConfig());
		try {
			TelemetryPublisher realPublisher = new TelemetryPublisher(new KafkaTemplate<>(producerFactory),
					JsonMapper.builder().build());

			Instant startedAt = Instant.now();
			assertThatThrownBy(() -> realPublisher.publish(successEvent("/api/products")))
				.isInstanceOf(KafkaException.class);

			assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(15));
		}
		finally {
			producerFactory.reset();
		}
	}

	private Map<String, Object> producerConfig() {
		return Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, UNREACHABLE_BROKER,
				ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
				ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
				ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS);
	}

	private void givenSendSucceeds(TelemetryEvent event) {
		when(this.objectMapper.writeValueAsString(event)).thenReturn(PAYLOAD);
		when(this.kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(completedSend());
	}

	private String capturedKey() {
		ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
		verify(this.kafkaTemplate).send(anyString(), key.capture(), anyString());
		return key.getValue();
	}

	/** JacksonException은 생성자가 protected이므로 익명 하위 Class로 만든다. */
	private JacksonException serializationFailure() {
		return new JacksonException("직렬화 실패") {
		};
	}

	@SuppressWarnings("unchecked")
	private static KafkaTemplate<String, String> mockKafkaTemplate() {
		return mock(KafkaTemplate.class);
	}

	@SuppressWarnings("unchecked")
	private static CompletableFuture<SendResult<String, String>> completedSend() {
		return CompletableFuture.completedFuture(mock(SendResult.class));
	}

	private TelemetryEvent successEvent(String endpoint) {
		return new TelemetryEvent("11111111-2222-3333-4444-555555555555", 1, Instant.parse("2026-08-31T12:00:00Z"),
				"demo-shop", "demo-shop-01", "99999999-8888-7777-6666-555555555555", "GET", endpoint, 200, true, 12L,
				null, null);
	}

}

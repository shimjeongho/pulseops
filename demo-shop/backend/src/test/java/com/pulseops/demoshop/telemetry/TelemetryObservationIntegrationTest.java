package com.pulseops.demoshop.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pulseops.demoshop.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Demo Shop의 HTTP Request가 공통 계층에서 어떤 Telemetry Event로 관측되는지 검증한다. Kafka는
 * 아직 없으므로 TelemetryPublisher를 Spy로 감싸 발행 결과를 직접 확인한다.
 * <p>
 * Route Template과 Business Error Code는 실제 Handler Mapping과 GlobalExceptionHandler가
 * 결정하므로 Mock Controller가 아니라 실제 API를 호출한다.
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "pulseops.telemetry.service-instance=demo-shop-test-01")
class TelemetryObservationIntegrationTest {

	private static final String SERVICE_INSTANCE = "demo-shop-test-01";

	private static final String REQUEST_ID_HEADER = RequestIdFilter.REQUEST_ID_HEADER;

	/** Test Context에서만 노출하는 오류 Endpoint이다. 상수여야 @GetMapping에 사용할 수 있다. */
	private static final String FAILING_ENDPOINT = "/api/telemetry-failure";

	private static final String UNAVAILABLE_ENDPOINT = "/api/telemetry-unavailable";

	private static final String FAILURE_MESSAGE = "관측 경로 확인을 위해 의도적으로 발생시킨 오류";

	private static final long SEEDED_ACTIVE_PRODUCT_ID = 1L;

	private static final long SEEDED_INACTIVE_PRODUCT_ID = 10L;

	private static final long UNKNOWN_PRODUCT_ID = 999999L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoSpyBean
	private TelemetryPublisher telemetryPublisher;

	@Value("${spring.application.name}")
	private String applicationName;

	// --- Request ID ---------------------------------------------------------

	@Test
	void generatesRequestIdWhenTheHeaderIsAbsent() throws Exception {
		String responseRequestId = this.mockMvc.perform(get("/api/products"))
			.andExpect(status().isOk())
			.andExpect(header().exists(REQUEST_ID_HEADER))
			.andReturn()
			.getResponse()
			.getHeader(REQUEST_ID_HEADER);

		assertThat(UUID.fromString(responseRequestId)).isNotNull();
		assertThat(publishedEvent().requestId()).isEqualTo(responseRequestId);
	}

	@Test
	void reusesTheRequestIdSuppliedByTheClient() throws Exception {
		String clientRequestId = "client-Request_1.0";

		this.mockMvc.perform(get("/api/products").header(REQUEST_ID_HEADER, clientRequestId))
			.andExpect(status().isOk())
			.andExpect(header().string(REQUEST_ID_HEADER, clientRequestId));

		assertThat(publishedEvent().requestId()).isEqualTo(clientRequestId);
	}

	/**
	 * 외부에서 온 값을 그대로 Header와 Event에 싣기 때문에 형식을 만족하지 않으면 버리고 새로
	 * 만든다. Response와 Event는 언제나 같은 값을 갖는다.
	 */
	@ParameterizedTest
	@MethodSource("invalidRequestIds")
	void replacesAnInvalidRequestIdWithAGeneratedOne(String invalidRequestId) throws Exception {
		String responseRequestId = this.mockMvc
			.perform(get("/api/products").header(REQUEST_ID_HEADER, invalidRequestId))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getHeader(REQUEST_ID_HEADER);

		assertThat(responseRequestId).isNotEqualTo(invalidRequestId);
		assertThat(UUID.fromString(responseRequestId)).isNotNull();
		assertThat(publishedEvent().requestId()).isEqualTo(responseRequestId);
	}

	static Stream<String> invalidRequestIds() {
		return Stream.of("", " ", "  ", "has space", "has/slash", "has\nbreak", "a".repeat(65));
	}

	// --- Route Template -----------------------------------------------------

	/**
	 * Spring Framework 7의 Handler Mapping이 Route Template을 어떤 타입으로 노출하는지 고정한다.
	 * TelemetryFilter는 이 attribute만으로 endpoint를 결정하므로 타입이 바뀌면 여기서 먼저 깨져야
	 * 한다.
	 */
	@Test
	void springExposesTheRouteTemplateAsAStringAttribute() throws Exception {
		Object bestMatchingPattern = this.mockMvc.perform(get("/api/products/{productId}", SEEDED_ACTIVE_PRODUCT_ID))
			.andReturn()
			.getRequest()
			.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);

		assertThat(bestMatchingPattern).isInstanceOf(String.class).hasToString("/api/products/{productId}");
	}

	/**
	 * 매칭되는 API Handler가 없어도 Static Resource Handler의 catch-all pattern이 attribute에
	 * 남는다. 따라서 attribute가 존재한다는 사실만으로 API Route라고 볼 수 없고 TelemetryFilter가
	 * 이를 걸러내야 한다.
	 */
	@Test
	void springLeavesTheStaticResourceCatchAllPatternForUnknownApiPaths() throws Exception {
		Object bestMatchingPattern = this.mockMvc.perform(get("/api/does-not-exist/12345"))
			.andReturn()
			.getRequest()
			.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);

		assertThat(bestMatchingPattern).hasToString("/**");
	}

	@Test
	void observesSuccessfulProductRequestWithItsRouteTemplate() throws Exception {
		this.mockMvc.perform(get("/api/products/{productId}", SEEDED_ACTIVE_PRODUCT_ID)).andExpect(status().isOk());

		TelemetryEvent event = publishedEvent();
		assertThat(event.schemaVersion()).isEqualTo(1);
		assertThat(event.method()).isEqualTo("GET");
		assertThat(event.endpoint()).isEqualTo("/api/products/{productId}");
		assertThat(event.httpStatus()).isEqualTo(200);
		assertThat(event.success()).isTrue();
		assertThat(event.errorType()).isNull();
		assertThat(event.errorMessage()).isNull();
		assertThat(UUID.fromString(event.eventId())).isNotNull();
	}

	@Test
	void observesOrderRequestWithItsRouteTemplate() throws Exception {
		Integer orderId = createOrder();
		clearInvocations(this.telemetryPublisher);

		this.mockMvc.perform(get("/api/orders/{orderId}", orderId)).andExpect(status().isOk());

		assertThat(publishedEvent().endpoint()).isEqualTo("/api/orders/{orderId}");
	}

	// --- 오류 분류 ----------------------------------------------------------

	@Test
	void observesBusinessNotFoundWithTheCodeAndMessageReturnedToTheClient() throws Exception {
		String responseBody = this.mockMvc.perform(get("/api/products/{productId}", UNKNOWN_PRODUCT_ID))
			.andExpect(status().isNotFound())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);

		TelemetryEvent event = publishedEvent();
		assertThat(event.endpoint()).isEqualTo("/api/products/{productId}");
		assertThat(event.httpStatus()).isEqualTo(404);
		assertThat(event.success()).isFalse();
		assertThat(event.errorType()).isEqualTo("PRODUCT_NOT_FOUND");
		assertThat(event.errorMessage()).isEqualTo(JsonPath.read(responseBody, "$.message"));
	}

	@Test
	void observesBusinessConflictWithTheCodeAndMessageReturnedToTheClient() throws Exception {
		String responseBody = this.mockMvc
			.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
				.content(orderJson(SEEDED_INACTIVE_PRODUCT_ID)))
			.andExpect(status().isConflict())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);

		TelemetryEvent event = publishedEvent();
		assertThat(event.endpoint()).isEqualTo("/api/orders");
		assertThat(event.method()).isEqualTo("POST");
		assertThat(event.httpStatus()).isEqualTo(409);
		assertThat(event.success()).isFalse();
		assertThat(event.errorType()).isEqualTo("PRODUCT_INACTIVE");
		assertThat(event.errorMessage()).isEqualTo(JsonPath.read(responseBody, "$.message"));
	}

	@Test
	void observesValidationFailureWithTheValidationErrorCode() throws Exception {
		String responseBody = this.mockMvc
			.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);

		TelemetryEvent event = publishedEvent();
		assertThat(event.endpoint()).isEqualTo("/api/orders");
		assertThat(event.httpStatus()).isEqualTo(400);
		assertThat(event.success()).isFalse();
		assertThat(event.errorType()).isEqualTo("VALIDATION_ERROR");
		assertThat(event.errorMessage()).isEqualTo(JsonPath.read(responseBody, "$.message"));
	}

	@Test
	void recordsUnknownApiPathAsUnmappedWithoutLeakingTheRawUri() throws Exception {
		this.mockMvc.perform(get("/api/does-not-exist/12345")).andExpect(status().isNotFound());

		TelemetryEvent event = publishedEvent();
		assertThat(event.endpoint()).isEqualTo("UNMAPPED").doesNotContain("12345");
		assertThat(event.httpStatus()).isEqualTo(404);
		assertThat(event.success()).isFalse();
		assertThat(event.errorType()).isEqualTo("UNHANDLED_CLIENT_ERROR");
		assertThat(event.errorMessage()).isNull();
	}

	@Test
	void recordsMethodNotAllowedAsAnUnhandledClientError() throws Exception {
		this.mockMvc.perform(put("/api/payments")).andExpect(status().isMethodNotAllowed());

		TelemetryEvent event = publishedEvent();
		assertThat(event.endpoint()).isEqualTo("UNMAPPED");
		assertThat(event.httpStatus()).isEqualTo(405);
		assertThat(event.errorType()).isEqualTo("UNHANDLED_CLIENT_ERROR");
	}

	/**
	 * 처리되지 않은 Exception이 chain 밖으로 올라오면 Response는 아직 최종 Status를 갖고 있지
	 * 않다. 관측은 Client가 결국 받게 되는 500으로 기록하되 Exception 자체는 그대로 전파해야 한다.
	 */
	@Test
	void observesUnhandledExceptionAsInternalServerErrorAndRethrowsIt() {
		assertThatThrownBy(() -> this.mockMvc.perform(get(FAILING_ENDPOINT))).hasMessageContaining(FAILURE_MESSAGE);

		TelemetryEvent event = publishedEvent();
		assertThat(event.endpoint()).isEqualTo(FAILING_ENDPOINT);
		assertThat(event.httpStatus()).isEqualTo(500);
		assertThat(event.success()).isFalse();
		assertThat(event.errorType()).isEqualTo("INTERNAL_SERVER_ERROR");
		assertThat(event.errorMessage()).isNull();
	}

	@Test
	void doesNotClassifyAServerErrorResponseAsAClientError() throws Exception {
		this.mockMvc.perform(get(UNAVAILABLE_ENDPOINT)).andExpect(status().isServiceUnavailable());

		TelemetryEvent event = publishedEvent();
		assertThat(event.httpStatus()).isEqualTo(503);
		assertThat(event.success()).isFalse();
		assertThat(event.errorType()).isEqualTo("INTERNAL_SERVER_ERROR");
	}

	// --- Event 필드 ---------------------------------------------------------

	@Test
	void identifiesTheLogicalServiceAndTheRunningInstance() throws Exception {
		this.mockMvc.perform(get("/api/products")).andExpect(status().isOk());

		TelemetryEvent event = publishedEvent();
		assertThat(event.service()).isEqualTo("demo-shop").isNotEqualTo(this.applicationName);
		assertThat(event.serviceInstance()).isEqualTo(SERVICE_INSTANCE);
	}

	@Test
	void measuresNonNegativeLatency() throws Exception {
		this.mockMvc.perform(get("/api/products")).andExpect(status().isOk());

		assertThat(publishedEvent().latencyMs()).isGreaterThanOrEqualTo(0L);
	}

	@Test
	void stampsOccurredAtWhenTheResponseIsDecided() throws Exception {
		Instant beforeRequest = Instant.now();

		this.mockMvc.perform(get("/api/products")).andExpect(status().isOk());

		Instant afterRequest = Instant.now();
		assertThat(publishedEvent().occurredAt()).isNotNull().isBetween(beforeRequest, afterRequest);
	}

	// --- 관측 범위와 격리 ---------------------------------------------------

	@Test
	void doesNotObserveRequestsOutsideTheApiPrefix() throws Exception {
		this.mockMvc.perform(get("/")).andExpect(header().exists(REQUEST_ID_HEADER));

		verify(this.telemetryPublisher, never()).publish(any());
	}

	/**
	 * 관측이 실패해도 Business 응답은 그대로여야 한다. 정상 응답과 오류 응답 양쪽을 확인한다.
	 */
	@Test
	void publisherFailureLeavesTheHttpResponseUnchanged() throws Exception {
		doThrow(new IllegalStateException("Telemetry 발행 실패")).when(this.telemetryPublisher).publish(any());

		this.mockMvc.perform(get("/api/products/{productId}", SEEDED_ACTIVE_PRODUCT_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value((int) SEEDED_ACTIVE_PRODUCT_ID));

		this.mockMvc.perform(get("/api/products/{productId}", UNKNOWN_PRODUCT_ID))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
			.andExpect(jsonPath("$.fieldErrors", hasSize(0)));
	}

	// --- 헬퍼 ---------------------------------------------------------------

	/**
	 * 하나의 HTTP Request가 정확히 하나의 Event만 만들었는지까지 함께 확인한다.
	 */
	private TelemetryEvent publishedEvent() {
		ArgumentCaptor<TelemetryEvent> published = ArgumentCaptor.forClass(TelemetryEvent.class);
		verify(this.telemetryPublisher).publish(published.capture());
		return published.getValue();
	}

	private Integer createOrder() throws Exception {
		String responseBody = this.mockMvc
			.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
				.content(orderJson(SEEDED_ACTIVE_PRODUCT_ID)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		return JsonPath.read(responseBody, "$.id");
	}

	private String orderJson(long productId) {
		return "{\"items\":[{\"productId\":%d,\"quantity\":1}]}".formatted(productId);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FailingEndpointConfiguration {

		@Bean
		FailingController failingController() {
			return new FailingController();
		}

	}

	/**
	 * 처리되지 않은 Exception 경로를 관측하기 위한 Endpoint이다. Demo Shop Production Code에
	 * 테스트 전용 분기나 Endpoint를 남기지 않으려고 Test Context에서만 등록한다.
	 */
	@RestController
	static class FailingController {

		@GetMapping(FAILING_ENDPOINT)
		void fail() {
			throw new IllegalStateException(FAILURE_MESSAGE);
		}

		/**
		 * Exception이 올라오지 않고도 5xx로 끝나는 경로이다. Demo Shop의 현재 API에는 이런
		 * Endpoint가 없지만 관측은 이 경우도 Client Error로 잘못 분류하지 않아야 한다.
		 */
		@GetMapping(UNAVAILABLE_ENDPOINT)
		ResponseEntity<Void> unavailable() {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
		}

	}

}

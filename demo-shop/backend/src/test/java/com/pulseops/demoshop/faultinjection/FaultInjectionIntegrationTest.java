package com.pulseops.demoshop.faultinjection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseops.demoshop.TestcontainersConfiguration;
import com.pulseops.demoshop.telemetry.TelemetryEvent;
import com.pulseops.demoshop.telemetry.TelemetryPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 장애 주입을 켠 상태에서 Header 하나가 실제 HTTP 응답과 관측 결과를 어떻게 바꾸는지 검증한다.
 * <p>
 * 주입한 장애가 관측까지 그대로 이어지는 것이 핵심이다. 주입 결과가 응답에만 남고 Telemetry에
 * 도달하지 않으면 PulseOps는 아무 일도 일어나지 않은 것으로 보게 된다.
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = { "demo-shop.fault-injection.enabled=true", "pulseops.telemetry.enabled=true",
		"spring.kafka.admin.auto-create=false" })
class FaultInjectionIntegrationTest {

	private static final String FAULT_HEADER = FaultInjectionInterceptor.FAULT_HEADER;

	private static final int SEEDED_PRODUCT_COUNT = 10;

	private static final long SEEDED_ACTIVE_PRODUCT_ID = 1L;

	/** PulseOps가 LATENCY를 CRITICAL로 보는 기준이다. 주입한 지연은 이보다 커야 의미가 있다. */
	private static final long LATENCY_CRITICAL_MS = 3000;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TelemetryPublisher telemetryPublisher;

	@Test
	void returnsServiceUnavailableWithTheCommonErrorContractForTheErrorFault() throws Exception {
		this.mockMvc.perform(get("/api/products").header(FAULT_HEADER, DemoFault.ERROR_503.name()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.status").value(503))
			.andExpect(jsonPath("$.code").value("DEMO_FAULT_INJECTED"))
			.andExpect(jsonPath("$.message").isString())
			.andExpect(jsonPath("$.path").value("/api/products"))
			.andExpect(jsonPath("$.timestamp").isString())
			.andExpect(jsonPath("$.fieldErrors", hasSize(0)));
	}

	/**
	 * 관측에 남는 오류 이름은 Java Exception Class 이름이 아니라 Client가 받은 Error Code여야
	 * 한다. Class 이름이 나가면 내부 구현이 노출되고, 이름을 바꾸는 순간 Metric의 기준도 바뀐다.
	 */
	@Test
	void observesTheErrorFaultWithItsStableErrorCode() throws Exception {
		this.mockMvc.perform(get("/api/products").header(FAULT_HEADER, DemoFault.ERROR_503.name()))
			.andExpect(status().isServiceUnavailable());

		TelemetryEvent event = publishedEvent();
		assertThat(event.service()).isEqualTo("demo-shop");
		assertThat(event.method()).isEqualTo("GET");
		assertThat(event.endpoint()).isEqualTo("/api/products");
		assertThat(event.httpStatus()).isEqualTo(503);
		assertThat(event.success()).isFalse();
		assertThat(event.errorType()).isEqualTo("DEMO_FAULT_INJECTED");
		assertThat(event.errorMessage()).isNotBlank();
		assertThat(event.latencyMs()).isNotNegative();
	}

	/**
	 * 장애를 주입해도 Route Template을 잃지 않아야 한다. UNMAPPED가 되면 Metric의 집계 단위가
	 * 사라져 어느 API가 아픈지 말할 수 없게 된다.
	 */
	@Test
	void keepsTheRouteTemplateWhenTheFaultedEndpointHasAPathVariable() throws Exception {
		this.mockMvc
			.perform(get("/api/products/{productId}", SEEDED_ACTIVE_PRODUCT_ID).header(FAULT_HEADER,
					DemoFault.ERROR_503.name()))
			.andExpect(status().isServiceUnavailable());

		TelemetryEvent event = publishedEvent();
		assertThat(event.endpoint()).isEqualTo("/api/products/{productId}")
			.doesNotContain(String.valueOf(SEEDED_ACTIVE_PRODUCT_ID));
	}

	/**
	 * 지연 유형은 응답을 실패로 만들지 않는다. 느린 것과 실패한 것은 다른 상태이고, PulseOps도
	 * 둘을 다른 Incident로 다룬다.
	 */
	@Test
	void delaysButStillSucceedsForTheLatencyFault() throws Exception {
		this.mockMvc.perform(get("/api/products").header(FAULT_HEADER, DemoFault.LATENCY_3500.name()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(SEEDED_PRODUCT_COUNT)));

		TelemetryEvent event = publishedEvent();
		assertThat(event.endpoint()).isEqualTo("/api/products");
		assertThat(event.httpStatus()).isEqualTo(200);
		assertThat(event.success()).isTrue();
		assertThat(event.errorType()).isNull();
		assertThat(event.errorMessage()).isNull();
		assertThat(event.latencyMs()).isGreaterThanOrEqualTo(LATENCY_CRITICAL_MS);
	}

	/**
	 * 알 수 없는 유형은 잘못된 요청이므로 서버 오류로 관측되지 않아야 한다. 500으로 끝나면 Demo
	 * 도중의 오타가 실제 장애처럼 오류율을 올린다.
	 */
	@Test
	void rejectsAnUnknownFaultTypeAsABadRequest() throws Exception {
		this.mockMvc.perform(get("/api/products").header(FAULT_HEADER, "UNKNOWN"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_DEMO_FAULT"));

		TelemetryEvent event = publishedEvent();
		assertThat(event.endpoint()).isEqualTo("/api/products");
		assertThat(event.httpStatus()).isEqualTo(400);
		assertThat(event.errorType()).isEqualTo("INVALID_DEMO_FAULT");
	}

	/**
	 * 장애 주입을 켜 두어도 Header 없는 요청은 그대로다. Demo 중에 들어온 일반 요청까지 함께
	 * 실패하면 요청 단위로 주입하는 의미가 없다.
	 */
	@Test
	void leavesRequestsWithoutTheHeaderUnchanged() throws Exception {
		this.mockMvc.perform(get("/api/products"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(SEEDED_PRODUCT_COUNT)));

		TelemetryEvent event = publishedEvent();
		assertThat(event.httpStatus()).isEqualTo(200);
		assertThat(event.success()).isTrue();
		assertThat(event.errorType()).isNull();
	}

	/**
	 * 주입한 Fault 유형은 Event에 실리지 않는다. Event는 Phase 2에서 정한 field만 가지며 Demo
	 * 전용 정보가 관측 계약에 섞이면 안 된다.
	 */
	@Test
	void doesNotCarryTheFaultHeaderIntoTheEvent() throws Exception {
		this.mockMvc.perform(get("/api/products").header(FAULT_HEADER, DemoFault.ERROR_503.name()))
			.andExpect(status().isServiceUnavailable());

		TelemetryEvent event = publishedEvent();
		assertThat(event.toString()).doesNotContain(DemoFault.ERROR_503.name()).doesNotContain(FAULT_HEADER);
	}

	private TelemetryEvent publishedEvent() {
		ArgumentCaptor<TelemetryEvent> published = ArgumentCaptor.forClass(TelemetryEvent.class);
		verify(this.telemetryPublisher).publish(published.capture());
		return published.getValue();
	}

}

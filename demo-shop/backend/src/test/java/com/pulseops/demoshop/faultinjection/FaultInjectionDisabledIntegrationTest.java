package com.pulseops.demoshop.faultinjection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseops.demoshop.TestcontainersConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 장애 주입을 켜지 않은 상태의 Demo Shop을 검증한다. 이것이 Production에서 실제로 실행되는
 * 형태다.
 * <p>
 * {@code demo-shop.fault-injection.enabled}를 일부러 지정하지 않는다. 기본값 자체가 비활성임을
 * 확인해야 하며, 테스트가 값을 직접 false로 주면 설정 기본값이 바뀌어도 이 테스트는 계속
 * 통과한다.
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "pulseops.telemetry.enabled=false")
class FaultInjectionDisabledIntegrationTest {

	private static final String FAULT_HEADER = FaultInjectionInterceptor.FAULT_HEADER;

	private static final int SEEDED_PRODUCT_COUNT = 10;

	/**
	 * 주입되는 지연(3.5초)보다 훨씬 짧으면서 평범한 조회 한 건에는 넉넉한 시간이다. 지연이 실제로
	 * 걸렸는지 여부만 가르면 되므로 정확한 응답 시간을 재지 않는다.
	 */
	private static final Duration NO_INJECTED_DELAY_LIMIT = Duration.ofSeconds(3);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ApplicationContext applicationContext;

	/**
	 * 값을 확인해 건너뛰는 것이 아니라 장애 주입 경로 자체가 없어야 한다. 구성 요소가 존재하지
	 * 않으면 실수로 활성화될 여지도 없다.
	 */
	@Test
	void doesNotRegisterTheFaultInjectionConfiguration() {
		assertThat(this.applicationContext.getBeanNamesForType(FaultInjectionConfiguration.class)).isEmpty();
	}

	/**
	 * 알 수 없는 값까지 포함해 Header가 아무 의미도 갖지 않아야 한다. 하나라도 응답이 달라지면
	 * Header를 아는 누구든 Demo Shop의 동작을 바꿀 수 있다는 뜻이다.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "ERROR_503", "LATENCY_3500", "UNKNOWN" })
	void ignoresTheFaultHeaderEntirely(String headerValue) throws Exception {
		this.mockMvc.perform(get("/api/products").header(FAULT_HEADER, headerValue))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(SEEDED_PRODUCT_COUNT)));
	}

	@Test
	void doesNotDelayTheRequestAskingForTheLatencyFault() throws Exception {
		long startNanos = System.nanoTime();

		this.mockMvc.perform(get("/api/products").header(FAULT_HEADER, DemoFault.LATENCY_3500.name()))
			.andExpect(status().isOk());

		assertThat(Duration.ofNanos(System.nanoTime() - startNanos)).isLessThan(NO_INJECTED_DELAY_LIMIT);
	}

}

package com.pulseops.demoshop.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;

/**
 * Telemetry Filter의 등록 조건과 실행 순서를 검증한다. 순서가 뒤집히면 TelemetryFilter가
 * requestId를 읽지 못하고, 관측 스위치가 동작하지 않으면 문제가 생겼을 때 관측만 끌 수 없다.
 */
class TelemetryConfigurationTest {

	private static final String REQUEST_ID_REGISTRATION = "requestIdFilterRegistration";

	private static final String TELEMETRY_REGISTRATION = "telemetryFilterRegistration";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TelemetryConfiguration.class);

	@Test
	void registersBothFiltersByDefault() {
		this.contextRunner.run((context) -> {
			assertThat(context).hasSingleBean(TelemetryPublisher.class);
			assertThat(registrationOf(context, REQUEST_ID_REGISTRATION).getFilter())
				.isInstanceOf(RequestIdFilter.class);
			assertThat(registrationOf(context, TELEMETRY_REGISTRATION).getFilter())
				.isInstanceOf(TelemetryFilter.class);
		});
	}

	@Test
	void runsRequestIdFilterBeforeTelemetryFilter() {
		this.contextRunner.run((context) -> assertThat(registrationOf(context, REQUEST_ID_REGISTRATION).getOrder())
			.isLessThan(registrationOf(context, TELEMETRY_REGISTRATION).getOrder()));
	}

	/**
	 * 관측을 꺼도 Request 식별자 부여는 남아야 한다. Client 추적과 Response Header는 Telemetry
	 * 발행 여부와 무관하기 때문이다.
	 */
	@Test
	void stopsObservingWhenTelemetryIsDisabled() {
		this.contextRunner.withPropertyValues("pulseops.telemetry.enabled=false").run((context) -> {
			assertThat(context).doesNotHaveBean(TELEMETRY_REGISTRATION);
			assertThat(context).hasBean(REQUEST_ID_REGISTRATION);
		});
	}

	@Test
	void bindsTelemetryPropertiesWithUsableDefaults() {
		this.contextRunner.run((context) -> {
			TelemetryProperties properties = context.getBean(TelemetryProperties.class);

			assertThat(properties.enabled()).isTrue();
			assertThat(properties.serviceInstance()).isNotBlank();
		});
	}

	@Test
	void bindsTheConfiguredServiceInstance() {
		this.contextRunner.withPropertyValues("pulseops.telemetry.service-instance=demo-shop-02")
			.run((context) -> assertThat(context.getBean(TelemetryProperties.class).serviceInstance())
				.isEqualTo("demo-shop-02"));
	}

	private FilterRegistrationBean<?> registrationOf(ApplicationContext context, String beanName) {
		return context.getBean(beanName, FilterRegistrationBean.class);
	}

}

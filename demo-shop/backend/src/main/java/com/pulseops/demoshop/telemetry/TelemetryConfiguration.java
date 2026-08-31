package com.pulseops.demoshop.telemetry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.servlet.filter.OrderedFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Telemetry 관측 구성 요소를 등록한다. Filter를 Component Scan에 맡기지 않고 여기서만 등록하여
 * 실행 순서와 활성화 조건을 한 곳에서 결정한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelemetryProperties.class)
public class TelemetryConfiguration {

	/**
	 * Request/Response를 감싸는 Framework Filter 뒤에서 동작해야 하므로
	 * {@link OrderedFilter#REQUEST_WRAPPER_FILTER_MAX_ORDER} 다음 구간을 사용한다.
	 */
	private static final int REQUEST_ID_FILTER_ORDER = OrderedFilter.REQUEST_WRAPPER_FILTER_MAX_ORDER + 10;

	/**
	 * RequestIdFilter가 식별자를 확정한 뒤에 실행되어야 Event에 실을 requestId를 항상 읽을 수
	 * 있다. 그래서 반드시 RequestIdFilter보다 뒤 순서를 갖는다.
	 */
	private static final int TELEMETRY_FILTER_ORDER = REQUEST_ID_FILTER_ORDER + 10;

	/**
	 * Request 식별자 부여는 외부 시스템에 의존하지 않고 Client 추적에도 쓰이므로 관측 스위치와
	 * 무관하게 항상 동작한다.
	 */
	@Bean
	FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
		FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
		registration.setOrder(REQUEST_ID_FILTER_ORDER);
		return registration;
	}

	@Bean
	@ConditionalOnProperty(prefix = "pulseops.telemetry", name = "enabled", matchIfMissing = true)
	FilterRegistrationBean<TelemetryFilter> telemetryFilterRegistration(TelemetryPublisher telemetryPublisher,
			TelemetryProperties telemetryProperties) {
		FilterRegistrationBean<TelemetryFilter> registration = new FilterRegistrationBean<>(
				new TelemetryFilter(telemetryPublisher, telemetryProperties));
		registration.setOrder(TELEMETRY_FILTER_ORDER);
		return registration;
	}

	@Bean
	TelemetryPublisher telemetryPublisher() {
		return new TelemetryPublisher();
	}

}

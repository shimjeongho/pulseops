package com.pulseops.demoshop.faultinjection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Header 하나를 어떤 결과로 바꾸는지만 검증한다. 설정에 따라 이 Interceptor가 등록되는지는
 * {@link FaultInjectionIntegrationTest}와 {@link FaultInjectionDisabledIntegrationTest}가 본다.
 * <p>
 * 지연 유형은 여기서 다루지 않는다. 확인하려면 실제로 3.5초를 기다려야 하고, 그 지연이 관측까지
 * 이어지는지는 Integration Test에서 한 번만 확인하는 편이 낫다.
 */
class FaultInjectionInterceptorTest {

	private final FaultInjectionInterceptor interceptor = new FaultInjectionInterceptor();

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "   " })
	void proceedsWhenNoFaultIsRequested(String headerValue) {
		assertThat(preHandleWith(headerValue)).isTrue();
	}

	@Test
	void injectsAServerFailureForTheErrorFault() {
		assertThatExceptionOfType(DemoFaultInjectedException.class)
			.isThrownBy(() -> preHandleWith(DemoFault.ERROR_503.name()));
	}

	/**
	 * 정해진 이름만 받아들인다. 비슷한 값을 알아서 해석하면 Demo에서 무엇이 실행됐는지 확신할 수
	 * 없고, 오타는 조용히 무시되는 대신 드러나야 한다.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "UNKNOWN", "error_503", "ERROR_500", "LATENCY", "ERROR_503 " })
	void rejectsAFaultTypeItDoesNotDefine(String headerValue) {
		assertThatExceptionOfType(InvalidDemoFaultException.class).isThrownBy(() -> preHandleWith(headerValue));
	}

	private boolean preHandleWith(String headerValue) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
		if (headerValue != null) {
			request.addHeader(FaultInjectionInterceptor.FAULT_HEADER, headerValue);
		}
		return this.interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
	}

}

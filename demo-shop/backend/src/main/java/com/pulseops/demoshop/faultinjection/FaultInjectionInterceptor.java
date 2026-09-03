package com.pulseops.demoshop.faultinjection;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 요청 Header로 지정된 장애를 그 요청 하나에만 주입한다.
 * <p>
 * 전역 스위치가 아니라 요청 단위로 동작한다. 서버에 "지금 고장난 상태"를 남기면 Demo 중에
 * 들어온 다른 요청까지 함께 실패하고, Demo가 끝난 뒤 상태를 되돌리는 일이 또 필요해진다.
 * 요청마다 결정하면 되돌릴 상태 자체가 없고 같은 시각에 정상 요청과 장애 요청을 함께 보낼 수
 * 있다.
 * <p>
 * Business Controller와 Service에는 장애 주입 코드를 넣지 않는다. Demo 전용 관심사가 Business
 * 흐름에 섞이면 어떤 조건에서 무엇이 실행되는지 읽기 어려워지고, 기능을 걷어낼 때 Business
 * 코드를 다시 건드려야 한다.
 * <p>
 * Filter가 아니라 Handler Interceptor인 이유는 두 가지다. Handler Mapping 이후에 실행되므로
 * Route Template이 이미 확정돼 있어 관측이 endpoint를 UNMAPPED로 잃지 않고, TelemetryFilter보다
 * 안쪽이라 주입한 결과가 실제 응답 그대로 관측된다.
 */
public class FaultInjectionInterceptor implements HandlerInterceptor {

	public static final String FAULT_HEADER = "X-Demo-Fault";

	/**
	 * PulseOps의 LATENCY CRITICAL 기준인 3000ms보다 여유 있게 큰 값이다. 기준선에 가까운 값을
	 * 쓰면 창마다 P95가 기준을 오르내려 Demo가 매번 다르게 끝난다.
	 */
	private static final long LATENCY_FAULT_DELAY_MILLIS = 3500;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		DemoFault fault = resolveFault(request.getHeader(FAULT_HEADER));
		if (fault == null) {
			return true;
		}
		// 기본 분기를 두지 않아 유형이 늘어나면 여기서 compile이 깨진다. 새 유형이 조용히
		// 아무 일도 하지 않는 것보다 낫다.
		return switch (fault) {
			case ERROR_503 -> throw new DemoFaultInjectedException();
			case LATENCY_3500 -> {
				delay(LATENCY_FAULT_DELAY_MILLIS);
				yield true;
			}
		};
	}

	/**
	 * Header가 없거나 비어 있으면 장애를 주입하지 않는다. 값을 알아볼 수 없을 때만 거부한다.
	 * 알 수 없는 값을 조용히 무시하면 Demo에서 장애가 발생하지 않은 이유를 알 수 없다.
	 */
	private DemoFault resolveFault(String headerValue) {
		if (headerValue == null || headerValue.isBlank()) {
			return null;
		}
		try {
			return DemoFault.valueOf(headerValue);
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidDemoFaultException();
		}
	}

	/**
	 * 장애 주입은 지연을 만드는 것 자체가 목적이므로 여기서만 Thread를 멈춘다. 지연 시간은
	 * 고정 상수이며 요청이 정하지 않으므로 무한정 기다리는 경우가 없다.
	 * <p>
	 * 중단 신호를 삼키면 Container가 Thread를 정리하지 못하므로 상태를 복원한 뒤 그대로 정상
	 * 처리를 이어 간다. 지연을 채우지 못한 것은 Business 실패가 아니다.
	 */
	private void delay(long delayMillis) {
		try {
			TimeUnit.MILLISECONDS.sleep(delayMillis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}

package com.pulseops.demoshop.faultinjection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 장애 주입을 개발 및 Demo 환경에서만 등록한다.
 * <p>
 * 기본값은 비활성이다. 설정이 없거나 false이면 이 Configuration 자체가 등록되지 않아
 * Interceptor가 존재하지 않고, 그러면 {@code X-Demo-Fault} Header는 아무 의미도 갖지 않는
 * 평범한 Header가 된다. 실행 중에 값을 확인해 건너뛰는 방식과 달리 장애 주입 경로가 아예
 * 없으므로, 설정을 켜지 않은 환경에서는 Demo Shop의 동작이 이 기능이 없을 때와 완전히 같다.
 * <p>
 * 안전 경계는 이 Backend 설정 하나다. Frontend에서 화면을 감추는 것은 안전장치가 아니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "demo-shop.fault-injection", name = "enabled")
public class FaultInjectionConfiguration implements WebMvcConfigurer {

	/** 장애를 주입할 대상은 관측 대상인 API뿐이다. */
	private static final String API_PATH_PATTERN = "/api/**";

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new FaultInjectionInterceptor()).addPathPatterns(API_PATH_PATTERN);
	}

}

package com.pulseops.demoshop.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Telemetry 관측 설정이다.
 *
 * @param enabled 관측 스위치. Telemetry가 Demo Shop에 영향을 주는 상황에서 재배포 없이 관측만 끌
 * 수 있어야 한다. Request 식별자 부여는 이 값과 무관하게 계속 동작한다.
 * @param serviceInstance 실행 Instance 식별자. 같은 service의 어느 Process가 만든 Event인지
 * 구분한다. hostname 탐색은 환경에 따라 이름 조회를 유발하므로 설정 값으로만 결정한다.
 */
@ConfigurationProperties("pulseops.telemetry")
public record TelemetryProperties(@DefaultValue("true") boolean enabled,
		@DefaultValue("demo-shop-local") String serviceInstance) {

}

package com.pulseops.metric;

import com.pulseops.telemetry.TelemetryEvent;

/**
 * Metric을 집계하는 단위이다.
 * <p>
 * Telemetry Event에서 "어떤 서비스의 어떤 API인가"만 남기고 나머지는 버린다. eventId, requestId처럼
 * Event마다 달라지는 값을 집계 기준에 넣으면 계열이 요청 수만큼 늘어나 집계 자체가 성립하지 않는다.
 * httpStatus와 latencyMs는 기준이 아니라 집계되는 값이므로 여기에 담지 않는다.
 * <p>
 * serviceInstance는 값이 유한하지만 Instance가 늘어날수록 같은 API의 Metric이 쪼개져 표본이
 * 흩어지므로 제외한다. 반대로 method는 포함한다. 같은 endpoint라도 조회와 생성은 응답 시간 분포와
 * 오류 성격이 서로 다르고, Demo Shop에는 실제로 {@code /api/orders}처럼 두 method를 함께 쓰는
 * Route가 있다.
 * <p>
 * endpoint는 실제 URI가 아니라 Route Template이거나 UNMAPPED라는 Phase 2 계약을 그대로 믿는다.
 * 여기서 다시 확인하면 같은 계약의 소유자가 둘이 된다.
 */
public record MetricSeries(String service, String method, String endpoint) {

	public static MetricSeries from(TelemetryEvent event) {
		return new MetricSeries(event.service(), event.method(), event.endpoint());
	}

	/**
	 * Redis Key의 endpoint 자리에 넣을 표기이다.
	 * <p>
	 * Route Template의 중괄호만 밑줄로 바꾼다. 중괄호는 Redis Cluster에서 Key의 어느 부분을 분산
	 * 기준으로 삼을지 지정하는 문법이라, 그대로 두면 {@code {productId}}가 Key 전체의 분산 기준이
	 * 된다. Hash나 Base64로 바꾸지 않는 이유는 운영 중에 Key만 보고 어느 API인지 알 수 있어야 하기
	 * 때문이다.
	 * <p>
	 * Key 전체 문자열을 만드는 것은 Redis를 아는 계층의 몫이고, 여기서는 endpoint 부분만 정한다.
	 */
	public String endpointKey() {
		return this.endpoint.replace('{', '_').replace('}', '_');
	}

}

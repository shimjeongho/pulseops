package com.pulseops.metric;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseops.telemetry.TelemetryEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 집계 단위가 무엇으로 결정되는지 고정한다.
 * <p>
 * 핵심 계약은 두 가지다. Event마다 달라지는 값이 집계 기준에 새어 들어가지 않는다는 것과, 같은
 * API의 Event는 결과가 어떻든 하나의 계열로 모인다는 것이다.
 */
class MetricSeriesTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-08-31T12:34:56.123Z");

	@Test
	void keepsOnlyServiceMethodAndEndpoint() {
		MetricSeries series = MetricSeries.from(event("demo-shop", "GET", "/api/products/{productId}"));

		assertThat(series).isEqualTo(new MetricSeries("demo-shop", "GET", "/api/products/{productId}"));
	}

	/**
	 * 성공한 요청과 실패한 요청은 eventId, requestId, serviceInstance, httpStatus, latencyMs,
	 * errorType이 모두 다르다. 그래도 같은 API를 부른 것이므로 같은 계열이어야 한다. 이 중 하나라도
	 * 집계 기준에 들어가면 계열이 쪼개져 Error Rate를 계산할 수 없다.
	 */
	@Test
	void ignoresValuesThatDifferBetweenEventsOfTheSameApi() {
		TelemetryEvent success = new TelemetryEvent("3f1c8a27-6b90-4d1e-9c44-72b0d5e19a2b", 1, OCCURRED_AT,
				"demo-shop", "demo-shop-01:8080", "a7d2f0c1-3e55-4a8b-b0d6-1f9c2ab84e10", "POST", "/api/payments",
				201, true, 12L, null, null);
		TelemetryEvent failure = new TelemetryEvent("0e5c6b1a-1111-4aaa-9bbb-0123456789ab", 1,
				OCCURRED_AT.plusSeconds(3), "demo-shop", "demo-shop-02:8080",
				"b8e3a1d2-4f66-4b9c-a1e7-2a0d3bc95f21", "POST", "/api/payments", 500, false, 480L,
				"INTERNAL_SERVER_ERROR", null);

		assertThat(MetricSeries.from(success)).isEqualTo(MetricSeries.from(failure));
	}

	@ParameterizedTest
	@CsvSource({ "/api/products/{productId},/api/products/_productId_", "/api/payments,/api/payments",
			"UNMAPPED,UNMAPPED" })
	void replacesRouteTemplateBracesInTheKeyForm(String endpoint, String expectedEndpointKey) {
		MetricSeries series = MetricSeries.from(event("demo-shop", "GET", endpoint));

		assertThat(series.endpointKey()).isEqualTo(expectedEndpointKey);
	}

	/** 집계 기준이 아닌 값은 이 테스트의 관심사가 아니므로 고정한다. */
	private static TelemetryEvent event(String service, String method, String endpoint) {
		return new TelemetryEvent("3f1c8a27-6b90-4d1e-9c44-72b0d5e19a2b", 1, OCCURRED_AT, service,
				"demo-shop-01:8080", "a7d2f0c1-3e55-4a8b-b0d6-1f9c2ab84e10", method, endpoint, 200, true, 12L, null,
				null);
	}

}

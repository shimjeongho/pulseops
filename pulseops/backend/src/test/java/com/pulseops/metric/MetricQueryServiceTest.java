package com.pulseops.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.pulseops.metric.dto.MetricListResponse;
import com.pulseops.metric.dto.MetricSummaryResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * 활성 계열을 모아 하나의 응답으로 만드는 규칙을 검증한다.
 * <p>
 * 저장소는 흉내 낸다. 여기서 보려는 것은 Redis에 무엇이 남는지가 아니라, 어떤 계열을 어떤 기준
 * 시각으로 읽고 어떤 순서로 내보내는지이기 때문이다. 실제 Redis 계약은
 * {@link RedisMetricStoreIntegrationTest}가 따로 고정한다.
 */
class MetricQueryServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-01T06:00:03Z");

	private static final MetricSeries PRODUCTS = new MetricSeries("demo-shop", "GET", "/api/products/{productId}");

	private static final MetricSeries ORDERS_POST = new MetricSeries("demo-shop", "POST", "/api/orders");

	private static final MetricSeries ORDERS_GET = new MetricSeries("demo-shop", "GET", "/api/orders");

	private RedisMetricStore metricStore;

	private RollingMetricService rollingMetricService;

	private MetricQueryService metricQueryService;

	@BeforeEach
	void createService() {
		this.metricStore = mock(RedisMetricStore.class);
		this.rollingMetricService = mock(RollingMetricService.class);
		this.metricQueryService = new MetricQueryService(this.metricStore, this.rollingMetricService);
	}

	/**
	 * 계열을 고르는 기준과 창을 자르는 기준이 다르면 한 응답 안의 줄들이 서로 다른 순간을 가리킨다.
	 */
	@Test
	void readsEverySeriesWithTheSameReferenceTime() {
		givenActiveSeries(PRODUCTS, ORDERS_POST);

		MetricListResponse response = this.metricQueryService.currentMetrics(NOW);

		assertThat(response.generatedAt()).isEqualTo(NOW);
		verify(this.metricStore).activeSeries(NOW);
		verify(this.rollingMetricService).snapshot(PRODUCTS, NOW);
		verify(this.rollingMetricService).snapshot(ORDERS_POST, NOW);
	}

	/**
	 * 값이 아니라 이름으로 정렬한다. 저장소가 돌려준 순서를 그대로 쓰면 다시 읽을 때마다 줄의 위치가
	 * 바뀌어 보고 있던 API를 따라갈 수 없다.
	 */
	@Test
	void ordersByServiceThenEndpointThenMethod() {
		givenActiveSeries(PRODUCTS, ORDERS_POST, ORDERS_GET);

		MetricListResponse response = this.metricQueryService.currentMetrics(NOW);

		assertThat(response.items()).extracting(MetricSummaryResponse::endpoint, MetricSummaryResponse::method)
			.containsExactly(tuple("/api/orders", "GET"),
					tuple("/api/orders", "POST"),
					tuple("/api/products/{productId}", "GET"));
	}

	@Test
	void mapsEveryFieldOfTheSnapshot() {
		given(this.metricStore.activeSeries(NOW)).willReturn(List.of(PRODUCTS));
		given(this.rollingMetricService.snapshot(PRODUCTS, NOW)).willReturn(new MetricSnapshot("demo-shop", "GET",
				"/api/products/{productId}", NOW.minusSeconds(60), NOW, 42L, 3L, 7.142857142857143, 380L));

		MetricSummaryResponse item = this.metricQueryService.currentMetrics(NOW).items().get(0);

		assertThat(item.service()).isEqualTo("demo-shop");
		assertThat(item.method()).isEqualTo("GET");
		assertThat(item.endpoint()).isEqualTo("/api/products/{productId}");
		assertThat(item.windowStart()).isEqualTo(NOW.minusSeconds(60));
		assertThat(item.windowEnd()).isEqualTo(NOW);
		assertThat(item.requestCount()).isEqualTo(42L);
		assertThat(item.errorCount()).isEqualTo(3L);
		assertThat(item.p95LatencyMs()).isEqualTo(380L);
	}

	/** Incident의 trigger_error_rate와 같은 자리수여야 같은 오류율이 화면에서 같은 숫자로 보인다. */
	@Test
	void roundsErrorRateToTwoDecimalPlaces() {
		given(this.metricStore.activeSeries(NOW)).willReturn(List.of(PRODUCTS));
		given(this.rollingMetricService.snapshot(PRODUCTS, NOW)).willReturn(snapshot(PRODUCTS, 7.142857142857143, 380L));

		MetricSummaryResponse item = this.metricQueryService.currentMetrics(NOW).items().get(0);

		assertThat(item.errorRate()).isEqualByComparingTo(new BigDecimal("7.14"));
		assertThat(item.errorRate().scale()).isEqualTo(2);
	}

	@Test
	void roundsHalfUp() {
		given(this.metricStore.activeSeries(NOW)).willReturn(List.of(PRODUCTS));
		given(this.rollingMetricService.snapshot(PRODUCTS, NOW)).willReturn(snapshot(PRODUCTS, 12.345, 10L));

		assertThat(this.metricQueryService.currentMetrics(NOW).items().get(0).errorRate())
			.isEqualByComparingTo(new BigDecimal("12.35"));
	}

	/** 표본이 없어 구하지 못한 P95를 0으로 바꾸면 응답 시간을 재지 못한 창이 가장 빠른 창이 된다. */
	@Test
	void keepsAMissingP95AsNull() {
		given(this.metricStore.activeSeries(NOW)).willReturn(List.of(PRODUCTS));
		given(this.rollingMetricService.snapshot(PRODUCTS, NOW)).willReturn(snapshot(PRODUCTS, 0.0, null));

		assertThat(this.metricQueryService.currentMetrics(NOW).items().get(0).p95LatencyMs()).isNull();
	}

	/** 관측 중인 계열이 없는 것은 정상이며 장애가 아니다. */
	@Test
	void returnsAnEmptyListWhenNoSeriesIsActive() {
		given(this.metricStore.activeSeries(NOW)).willReturn(List.of());

		MetricListResponse response = this.metricQueryService.currentMetrics(NOW);

		assertThat(response.items()).isEmpty();
		assertThat(response.generatedAt()).isEqualTo(NOW);
	}

	/**
	 * 계열 목록을 읽지 못한 것을 빈 목록으로 바꾸면 저장소 장애가 "관측 중인 API 없음"과 같은 화면이
	 * 된다.
	 */
	@Test
	void doesNotTurnASeriesLookupFailureIntoAnEmptyList() {
		given(this.metricStore.activeSeries(NOW)).willThrow(new RedisConnectionFailureException("down"));

		assertThatExceptionOfType(MetricStoreUnavailableException.class)
			.isThrownBy(() -> this.metricQueryService.currentMetrics(NOW));
	}

	/**
	 * 계열 하나를 읽지 못했다면 나머지만 담아 돌려주지 않는다. 무엇이 빠졌는지 알 수 없는 목록은
	 * 오히려 잘못된 판단의 근거가 된다.
	 */
	@Test
	void doesNotReturnAPartialListWhenOneSnapshotFails() {
		givenActiveSeries(PRODUCTS, ORDERS_POST);
		given(this.rollingMetricService.snapshot(ORDERS_POST, NOW))
			.willThrow(new RedisConnectionFailureException("down"));

		assertThatExceptionOfType(MetricStoreUnavailableException.class)
			.isThrownBy(() -> this.metricQueryService.currentMetrics(NOW));
	}

	private void givenActiveSeries(MetricSeries... series) {
		given(this.metricStore.activeSeries(NOW)).willReturn(List.of(series));
		for (MetricSeries each : series) {
			given(this.rollingMetricService.snapshot(eq(each), any())).willReturn(snapshot(each, 0.0, 10L));
		}
	}

	private static MetricSnapshot snapshot(MetricSeries series, double errorRate, Long p95LatencyMs) {
		return new MetricSnapshot(series.service(), series.method(), series.endpoint(), NOW.minusSeconds(60), NOW, 10L,
				0L, errorRate, p95LatencyMs);
	}

}

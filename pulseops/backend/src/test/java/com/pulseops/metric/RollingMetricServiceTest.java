package com.pulseops.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * 창 하나를 읽을 때 어느 Bucket을 어떤 경계로 읽는지를 Redis 없이 고정한다.
 * <p>
 * 여기서 보는 것은 계산 결과가 아니라 연결이다. 저장소에 넘기는 Bucket이 창과 같은지, 창의 경계가
 * Snapshot에 그대로 실리는지, 그리고 Redis 장애가 빈 Snapshot으로 둔갑하지 않는지를 본다.
 */
class RollingMetricServiceTest {

	private static final MetricSeries SERIES = new MetricSeries("demo-shop", "GET", "/api/products/{productId}");

	private static final Instant NOW = Instant.parse("2026-08-31T12:34:56.789Z");

	private final RedisMetricStore metricStore = mock(RedisMetricStore.class);

	private final RollingMetricService rollingMetricService = new RollingMetricService(this.metricStore);

	/** 창을 나누는 규칙이 이 Class에 복사되면 쓰는 쪽과 읽는 쪽의 Bucket이 따로 놀 수 있다. */
	@Test
	void readsExactlyTheBucketsOfTheWindow() {
		givenStoredWindow(new MetricWindowData(0, 0, List.of()));

		this.rollingMetricService.snapshot(SERIES, NOW);

		then(this.metricStore).should().read(SERIES, MetricWindow.bucketStartsFor(NOW));
	}

	/** 마지막 Bucket은 아직 채워지는 중이지만 창에 들어와야 방금 시작된 이상이 보인다. */
	@Test
	void reportsASixtySecondWindowThatIncludesTheCurrentBucket() {
		givenStoredWindow(new MetricWindowData(0, 0, List.of()));

		MetricSnapshot snapshot = this.rollingMetricService.snapshot(SERIES, NOW);

		assertThat(snapshot.windowStart()).isEqualTo(Instant.parse("2026-08-31T12:34:00Z"));
		assertThat(snapshot.windowEnd()).isEqualTo(Instant.parse("2026-08-31T12:35:00Z"));
		assertThat(Duration.between(snapshot.windowStart(), snapshot.windowEnd())).isEqualTo(Duration.ofSeconds(60));
	}

	/** 파생 값은 저장소가 아니라 Snapshot이 계산한다. 읽어 온 숫자가 그 계산에 그대로 들어가는지만 본다. */
	@Test
	void derivesTheSnapshotFromWhatTheStoreReturned() {
		givenStoredWindow(new MetricWindowData(10, 1, List.of(30L, 10L, 20L)));

		MetricSnapshot snapshot = this.rollingMetricService.snapshot(SERIES, NOW);

		assertThat(snapshot.requestCount()).isEqualTo(10);
		assertThat(snapshot.errorCount()).isEqualTo(1);
		assertThat(snapshot.errorRate()).isEqualTo(10.0);
		assertThat(snapshot.p95LatencyMs()).isEqualTo(30L);
	}

	/**
	 * 읽지 못한 것과 요청이 없었던 것은 다른 상태다. 장애를 0으로 바꿔 돌려주면 읽는 쪽에는 조용한
	 * 서비스로 보인다.
	 */
	@Test
	void doesNotTurnARedisFailureIntoAnEmptySnapshot() {
		given(this.metricStore.read(any(), any())).willThrow(new RedisConnectionFailureException("연결 실패"));

		assertThatExceptionOfType(RedisConnectionFailureException.class)
			.isThrownBy(() -> this.rollingMetricService.snapshot(SERIES, NOW));
	}

	private void givenStoredWindow(MetricWindowData windowData) {
		given(this.metricStore.read(eq(SERIES), any())).willReturn(windowData);
	}

}

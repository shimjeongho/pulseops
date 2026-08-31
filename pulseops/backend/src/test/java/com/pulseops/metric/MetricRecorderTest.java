package com.pulseops.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.pulseops.telemetry.TelemetryEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

/**
 * Event 하나가 집계에 들어가는지, 어떤 값으로 들어가는지를 Redis 없이 고정한다.
 * <p>
 * 여기서 고정하는 계약은 세 가지다. 오류로 세는 경계, 집계 창 밖의 Event는 저장소에 닿지도 않는다는
 * 것, 그리고 Redis 장애는 흡수하되 Metric 계층 자체의 결함은 흡수하지 않는다는 것이다.
 * <p>
 * 현재 시각은 테스트가 정한다. 시각에 따라 결과가 달라지는 판단을 실제 시계로 검증하면 경계값을
 * 확인할 방법이 없다.
 */
@ExtendWith(OutputCaptureExtension.class)
class MetricRecorderTest {

	private static final Instant NOW = Instant.parse("2026-08-31T12:34:56.789Z");

	private static final String EVENT_ID = "3f1c8a27-6b90-4d1e-9c44-72b0d5e19a2b";

	private static final String REDIS_FAILURE_LOG_MARKER = "Redis에 Metric을 기록하지 못해";

	private final RedisMetricStore metricStore = mock(RedisMetricStore.class);

	private final MetricRecorder recorder = new MetricRecorder(this.metricStore);

	@ParameterizedTest
	@ValueSource(ints = { 199, 200, 201, 299, 399, 400, 404, 409, 499 })
	void doesNotCountStatusBelowFiveHundredAsError(int httpStatus) {
		assertThat(MetricRecorder.isMetricError(httpStatus)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(ints = { 500, 502, 503, 599 })
	void countsServerErrorStatusAsError(int httpStatus) {
		assertThat(MetricRecorder.isMetricError(httpStatus)).isTrue();
	}

	/** 4xx는 서비스가 응답에 실패한 것이 아니므로 오류가 아니지만, 요청 수에는 들어가야 한다. */
	@ParameterizedTest
	@ValueSource(ints = { 404, 409 })
	void sendsClientErrorAsARequestWithoutError(int httpStatus) {
		TelemetryEvent event = event(NOW, httpStatus, 12L);

		this.recorder.record(event, NOW);

		then(this.metricStore).should()
			.record(EVENT_ID, MetricSeries.from(event), MetricWindow.bucketStartOf(NOW), false, 12L);
	}

	@Test
	void sendsServerErrorAsAnError() {
		TelemetryEvent event = event(NOW, 500, 480L);

		this.recorder.record(event, NOW);

		then(this.metricStore).should()
			.record(EVENT_ID, MetricSeries.from(event), MetricWindow.bucketStartOf(NOW), true, 480L);
	}

	/** Event가 속한 Bucket은 도착 시각이 아니라 요청이 끝난 시각으로 정해진다. */
	@Test
	void placesTheEventInTheBucketOfItsOwnInstant() {
		Instant occurredAt = NOW.minusSeconds(37);

		this.recorder.record(event(occurredAt, 200, 12L), NOW);

		then(this.metricStore).should()
			.record(any(), any(), eq(MetricWindow.bucketStartOf(occurredAt)), anyBoolean(), anyLong());
	}

	@Test
	void skipsEventOlderThanTheAcceptWindow() {
		this.recorder.record(event(NOW.minusSeconds(301), 200, 12L), NOW);

		then(this.metricStore).shouldHaveNoInteractions();
	}

	@Test
	void recordsEventAtTheOldestAcceptedInstant() {
		this.recorder.record(event(NOW.minusSeconds(300), 200, 12L), NOW);

		then(this.metricStore).should().record(any(), any(), any(), anyBoolean(), anyLong());
	}

	@Test
	void skipsEventTooFarInTheFuture() {
		this.recorder.record(event(NOW.plusSeconds(6), 200, 12L), NOW);

		then(this.metricStore).shouldHaveNoInteractions();
	}

	@Test
	void recordsEventAtTheFurthestAcceptedFutureInstant() {
		this.recorder.record(event(NOW.plusSeconds(5), 200, 12L), NOW);

		then(this.metricStore).should().record(any(), any(), any(), anyBoolean(), anyLong());
	}

	/**
	 * 같은 Event가 다시 오는 것은 at-least-once 전달에서 정상이다. 경고로 남기면 정상 동작이 장애처럼
	 * 보인다.
	 */
	@Test
	void acceptsAnAlreadyRecordedEventWithoutWarning(CapturedOutput output) {
		given(this.metricStore.record(any(), any(), any(), anyBoolean(), anyLong())).willReturn(false);

		assertThatCode(() -> this.recorder.record(event(NOW, 200, 12L), NOW)).doesNotThrowAnyException();

		assertThat(output).doesNotContain(REDIS_FAILURE_LOG_MARKER);
	}

	/** Metric을 남기지 못한 것이 Event 처리 실패가 되면 Consumer가 같은 offset에서 멈춘다. */
	@Test
	void absorbsRedisFailure(CapturedOutput output) {
		given(this.metricStore.record(any(), any(), any(), anyBoolean(), anyLong()))
			.willThrow(new RedisConnectionFailureException("Redis에 연결할 수 없다"));

		assertThatCode(() -> this.recorder.record(event(NOW, 200, 12L), NOW)).doesNotThrowAnyException();

		assertThat(output).contains(REDIS_FAILURE_LOG_MARKER).contains(EVENT_ID);
	}

	@Test
	void absorbsRedisScriptFailure(CapturedOutput output) {
		given(this.metricStore.record(any(), any(), any(), anyBoolean(), anyLong()))
			.willThrow(new RedisSystemException("script 실행에 실패했다", new IllegalStateException("Lua error")));

		assertThatCode(() -> this.recorder.record(event(NOW, 200, 12L), NOW)).doesNotThrowAnyException();

		assertThat(output).contains(REDIS_FAILURE_LOG_MARKER);
	}

	/**
	 * Redis 장애만 흡수한다. 나머지까지 삼키면 Metric 계층의 결함이 log 한 줄로 묻히고 잘못된 값이
	 * 정상처럼 쌓인다.
	 */
	@Test
	void doesNotHideFailuresOtherThanRedis() {
		given(this.metricStore.record(any(), any(), any(), anyBoolean(), anyLong()))
			.willThrow(new IllegalStateException("Metric 계층의 결함"));

		assertThatIllegalStateException().isThrownBy(() -> this.recorder.record(event(NOW, 200, 12L), NOW));
	}

	/** 시각을 넘기지 않는 실제 호출 경로도 방금 끝난 요청을 집계에 넣어야 한다. */
	@Test
	void recordsARecentEventWhenNoInstantIsGiven() {
		this.recorder.record(event(Instant.now(), 200, 12L));

		then(this.metricStore).should().record(any(), any(), any(), anyBoolean(), anyLong());
	}

	/** 집계 기준이 아닌 값은 이 테스트의 관심사가 아니므로 고정한다. */
	private static TelemetryEvent event(Instant occurredAt, int httpStatus, long latencyMs) {
		return new TelemetryEvent(EVENT_ID, 1, occurredAt, "demo-shop", "demo-shop-01:8080",
				"a7d2f0c1-3e55-4a8b-b0d6-1f9c2ab84e10", "GET", "/api/products/{productId}", httpStatus,
				httpStatus < 400, latencyMs, null, null);
	}

}

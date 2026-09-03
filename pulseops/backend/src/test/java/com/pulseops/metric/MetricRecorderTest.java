package com.pulseops.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
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
 * 여기서 고정하는 계약은 네 가지다. 오류로 세는 경계, 집계 창 밖의 Event는 저장소에 닿지도 않는다는
 * 것, Redis 장애는 흡수하되 Metric 계층 자체의 결함은 흡수하지 않는다는 것, 그리고 이 Event로 Metric이
 * 실제로 달라졌는지를 돌려준다는 것이다.
 * <p>
 * 마지막 계약이 뒤따르는 이상 판정의 방아쇠가 된다. 달라지지 않은 Event를 달라졌다고 말하면 같은 창이
 * 반복해서 다시 평가되고, 반대로 달라진 Event를 아니라고 말하면 이상이 시작된 창이 판정되지 않는다.
 * <p>
 * 현재 시각은 테스트가 정한다. 시각에 따라 결과가 달라지는 판단을 실제 시계로 검증하면 경계값을
 * 확인할 방법이 없다.
 */
@ExtendWith(OutputCaptureExtension.class)
class MetricRecorderTest {

	private static final Instant NOW = Instant.parse("2026-08-31T12:34:56.789Z");

	private static final String EVENT_ID = "3f1c8a27-6b90-4d1e-9c44-72b0d5e19a2b";

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

	/** 반영되었다는 사실이 뒤따르는 이상 판정의 방아쇠이므로 성공 경로의 반환값을 고정한다. */
	@Test
	void reportsThatTheMetricChanged() {
		givenStoreApplied(true);

		assertThat(this.recorder.record(event(NOW, 200, 12L), NOW)).isEqualTo(MetricRecordOutcome.APPLIED);
	}

	/**
	 * 재전달된 Event는 Redis dedup에 걸려 Metric을 바꾸지 못한다. 그 사실이 그대로 밖으로 나가야
	 * 같은 요청이 두 번 세어지지 않은 것처럼 판정도 두 번 돌지 않는다.
	 */
	@Test
	void reportsThatARedeliveredEventChangedNothing() {
		given(this.metricStore.record(any(), any(), any(), anyBoolean(), anyLong())).willReturn(true, false);

		assertThat(this.recorder.record(event(NOW, 200, 12L), NOW)).isEqualTo(MetricRecordOutcome.APPLIED);
		assertThat(this.recorder.record(event(NOW, 200, 12L), NOW)).isEqualTo(MetricRecordOutcome.DUPLICATE);
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
		assertThat(this.recorder.record(event(NOW.minusSeconds(301), 200, 12L), NOW))
			.isEqualTo(MetricRecordOutcome.OUT_OF_WINDOW);

		then(this.metricStore).shouldHaveNoInteractions();
	}

	@Test
	void recordsEventAtTheOldestAcceptedInstant() {
		givenStoreApplied(true);

		assertThat(this.recorder.record(event(NOW.minusSeconds(300), 200, 12L), NOW))
			.isEqualTo(MetricRecordOutcome.APPLIED);

		then(this.metricStore).should().record(any(), any(), any(), anyBoolean(), anyLong());
	}

	@Test
	void skipsEventTooFarInTheFuture() {
		assertThat(this.recorder.record(event(NOW.plusSeconds(6), 200, 12L), NOW))
			.isEqualTo(MetricRecordOutcome.OUT_OF_WINDOW);

		then(this.metricStore).shouldHaveNoInteractions();
	}

	@Test
	void recordsEventAtTheFurthestAcceptedFutureInstant() {
		givenStoreApplied(true);

		assertThat(this.recorder.record(event(NOW.plusSeconds(5), 200, 12L), NOW))
			.isEqualTo(MetricRecordOutcome.APPLIED);

		then(this.metricStore).should().record(any(), any(), any(), anyBoolean(), anyLong());
	}

	/**
	 * 같은 Event가 다시 오는 것은 at-least-once 전달에서 정상이다. 경고로 남기면 정상 동작이 장애처럼
	 * 보인다.
	 */
	@Test
	void acceptsAnAlreadyRecordedEventWithoutWarning(CapturedOutput output) {
		givenStoreApplied(false);

		assertThat(this.recorder.record(event(NOW, 200, 12L), NOW)).isEqualTo(MetricRecordOutcome.DUPLICATE);

		assertThat(output).doesNotContain("WARN");
	}

	/**
	 * Redis 장애를 결과 값으로 바꾸지 않는다. 여기서 삼키면 Redis가 잠깐 흔들린 구간의 Event가 영구히
	 * 사라지지만, 올려 보내면 Consumer의 재시도가 같은 Event를 다시 반영할 수 있다.
	 */
	@Test
	void propagatesRedisConnectionFailureSoTheConsumerCanRetry() {
		given(this.metricStore.record(any(), any(), any(), anyBoolean(), anyLong()))
			.willThrow(new RedisConnectionFailureException("Redis에 연결할 수 없다"));

		assertThatExceptionOfType(RedisConnectionFailureException.class)
			.isThrownBy(() -> this.recorder.record(event(NOW, 200, 12L), NOW));
	}

	@Test
	void propagatesRedisScriptFailureSoTheConsumerCanRetry() {
		given(this.metricStore.record(any(), any(), any(), anyBoolean(), anyLong()))
			.willThrow(new RedisSystemException("script 실행에 실패했다", new IllegalStateException("Lua error")));

		assertThatExceptionOfType(RedisSystemException.class)
			.isThrownBy(() -> this.recorder.record(event(NOW, 200, 12L), NOW));
	}

	/** 저장소 장애가 아닌 결함도 그대로 올라가야 잘못 계산된 Metric이 정상처럼 쌓이지 않는다. */
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

	private void givenStoreApplied(boolean applied) {
		given(this.metricStore.record(any(), any(), any(), anyBoolean(), anyLong())).willReturn(applied);
	}

	/** 집계 기준이 아닌 값은 이 테스트의 관심사가 아니므로 고정한다. */
	private static TelemetryEvent event(Instant occurredAt, int httpStatus, long latencyMs) {
		return new TelemetryEvent(EVENT_ID, 1, occurredAt, "demo-shop", "demo-shop-01:8080",
				"a7d2f0c1-3e55-4a8b-b0d6-1f9c2ab84e10", "GET", "/api/products/{productId}", httpStatus,
				httpStatus < 400, latencyMs, null, null);
	}

}

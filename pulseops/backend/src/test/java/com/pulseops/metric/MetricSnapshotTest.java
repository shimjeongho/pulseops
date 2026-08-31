package com.pulseops.metric;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 창의 원본 숫자에서 Error Rate와 P95를 어떻게 얻는지 고정한다. */
class MetricSnapshotTest {

	private static final MetricSeries SERIES = new MetricSeries("demo-shop", "GET", "/api/products/{productId}");

	private static final Instant WINDOW_START = Instant.parse("2026-08-31T12:34:00Z");

	private static final Instant WINDOW_END = Instant.parse("2026-08-31T12:35:00Z");

	@Test
	void carriesTheSeriesDimensionsAndWindowBounds() {
		MetricSnapshot snapshot = snapshotOf(new MetricWindowData(3, 1, List.of(10L, 20L, 30L)));

		assertThat(snapshot.service()).isEqualTo("demo-shop");
		assertThat(snapshot.method()).isEqualTo("GET");
		assertThat(snapshot.endpoint()).isEqualTo("/api/products/{productId}");
		assertThat(snapshot.windowStart()).isEqualTo(WINDOW_START);
		assertThat(snapshot.windowEnd()).isEqualTo(WINDOW_END);
		assertThat(snapshot.requestCount()).isEqualTo(3);
		assertThat(snapshot.errorCount()).isEqualTo(1);
	}

	// --- Error Rate ---------------------------------------------------------

	@ParameterizedTest
	@CsvSource({ "0,0,0.0", "1,1,100.0", "4,1,25.0", "10,0,0.0" })
	void computesErrorRateAsAPercentage(long requestCount, long errorCount, double expectedErrorRate) {
		MetricSnapshot snapshot = snapshotOf(new MetricWindowData(requestCount, errorCount, List.of()));

		assertThat(snapshot.errorRate()).isEqualTo(expectedErrorRate);
	}

	/** 트래픽이 없었다는 사실은 errorRate가 아니라 requestCount에 남는다. */
	@Test
	void reportsNoTrafficAsZeroRequestsRatherThanHidingItInTheErrorRate() {
		MetricSnapshot snapshot = snapshotOf(new MetricWindowData(0, 0, List.of()));

		assertThat(snapshot.requestCount()).isZero();
		assertThat(snapshot.errorRate()).isZero();
	}

	// --- P95 ----------------------------------------------------------------

	@Test
	void hasNoP95WhenThereAreNoSamples() {
		MetricSnapshot snapshot = snapshotOf(new MetricWindowData(0, 0, List.of()));

		assertThat(snapshot.p95LatencyMs()).isNull();
	}

	/** 표본을 1..n으로 두면 기대값이 곧 Nearest Rank 순위와 같아진다. */
	@ParameterizedTest
	@CsvSource({ "1,1", "2,2", "3,3", "20,19", "40,38", "100,95" })
	void picksTheNearestRankSample(int sampleCount, long expectedP95) {
		List<Long> latencySamples = LongStream.rangeClosed(1, sampleCount).boxed().toList();

		MetricSnapshot snapshot = snapshotOf(new MetricWindowData(sampleCount, 0, latencySamples));

		assertThat(snapshot.p95LatencyMs()).isEqualTo(expectedP95);
	}

	/**
	 * 부동소수로 순위를 구하면 {@code 0.95 * 20}이 19.000000000000004가 되어 순위가 20으로 올라가고
	 * 색인이 표본 범위를 벗어난다. 19번째로 작은 값, 즉 색인 18의 값이 나와야 한다.
	 */
	@Test
	void takesTheNineteenthSmallestSampleOutOfTwenty() {
		List<Long> latencySamples = LongStream.rangeClosed(1, 20).boxed().toList();

		MetricSnapshot snapshot = snapshotOf(new MetricWindowData(20, 0, latencySamples));

		assertThat(snapshot.p95LatencyMs()).isEqualTo(19L).isEqualTo(latencySamples.get(18));
	}

	@Test
	void sortsSamplesBeforePickingTheP95() {
		List<Long> latencySamples = List.of(120L, 5L, 900L, 40L, 7L, 300L, 60L, 20L, 15L, 80L);

		MetricSnapshot snapshot = snapshotOf(new MetricWindowData(10, 0, latencySamples));

		assertThat(snapshot.p95LatencyMs()).isEqualTo(900L);
	}

	@Test
	void returnsTheRepeatedValueWhenEverySampleIsTheSame() {
		MetricSnapshot snapshot = snapshotOf(new MetricWindowData(20, 0, Collections.nCopies(20, 42L)));

		assertThat(snapshot.p95LatencyMs()).isEqualTo(42L);
	}

	/** 같은 값이 여러 번 나와도 각각 하나의 표본이므로, 느린 요청 하나가 P95를 끌어올리지 못한다. */
	@Test
	void countsDuplicateSamplesIndividuallyWhenRanking() {
		List<Long> latencySamples = new ArrayList<>(Collections.nCopies(19, 1L));
		latencySamples.add(500L);

		MetricSnapshot snapshot = snapshotOf(new MetricWindowData(20, 0, latencySamples));

		assertThat(snapshot.p95LatencyMs()).isEqualTo(1L);
	}

	@Test
	void leavesTheGivenSampleOrderUnchanged() {
		List<Long> latencySamples = new ArrayList<>(List.of(120L, 5L, 900L, 40L));

		snapshotOf(new MetricWindowData(4, 0, latencySamples));

		assertThat(latencySamples).containsExactly(120L, 5L, 900L, 40L);
	}

	private static MetricSnapshot snapshotOf(MetricWindowData data) {
		return MetricSnapshot.of(SERIES, WINDOW_START, WINDOW_END, data);
	}

}

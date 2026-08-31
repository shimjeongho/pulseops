package com.pulseops.metric;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Bucket 경계와 창의 범위를 고정한다.
 * <p>
 * 이 경계는 쓰는 쪽과 읽는 쪽이 함께 쓰므로 한 칸만 어긋나도 방금 기록한 Event가 창에서 빠지거나
 * 이미 지난 구간이 창에 들어온다.
 */
class MetricWindowTest {

	private static final Instant NOW = Instant.parse("2026-08-31T12:34:56.789Z");

	@ParameterizedTest
	@CsvSource({ "2026-08-31T12:34:00.000Z,2026-08-31T12:34:00Z", "2026-08-31T12:34:09.999Z,2026-08-31T12:34:00Z",
			"2026-08-31T12:34:10.000Z,2026-08-31T12:34:10Z", "2026-08-31T12:34:59.999Z,2026-08-31T12:34:50Z",
			"2026-08-31T12:35:00.000Z,2026-08-31T12:35:00Z" })
	void placesAnInstantInItsTenSecondBucket(String occurredAt, String expectedBucketStart) {
		assertThat(MetricWindow.bucketStartOf(Instant.parse(occurredAt)))
			.isEqualTo(Instant.parse(expectedBucketStart));
	}

	@Test
	void returnsSixBucketStartsFromOldestToNewest() {
		List<Instant> bucketStarts = MetricWindow.bucketStartsFor(NOW);

		assertThat(bucketStarts).hasSize(MetricWindow.WINDOW_BUCKETS).isSorted();
	}

	@Test
	void spacesBucketStartsTenSecondsApart() {
		List<Instant> bucketStarts = MetricWindow.bucketStartsFor(NOW);

		assertThat(bucketStarts).containsExactly(Instant.parse("2026-08-31T12:34:00Z"),
				Instant.parse("2026-08-31T12:34:10Z"), Instant.parse("2026-08-31T12:34:20Z"),
				Instant.parse("2026-08-31T12:34:30Z"), Instant.parse("2026-08-31T12:34:40Z"),
				Instant.parse("2026-08-31T12:34:50Z"));
	}

	/** 지금 채워지는 중인 Bucket이 빠지면 방금 들어온 Event가 창에서 보이지 않는다. */
	@Test
	void endsWithTheBucketThatHoldsNow() {
		List<Instant> bucketStarts = MetricWindow.bucketStartsFor(NOW);

		assertThat(bucketStarts.getLast()).isEqualTo(MetricWindow.bucketStartOf(NOW));
	}

	@Test
	void startsTheWindowAtTheOldestBucket() {
		assertThat(MetricWindow.bucketStartsFor(NOW).getFirst()).isEqualTo(MetricWindow.windowStart(NOW));
	}

	@Test
	void coversExactlySixtySeconds() {
		Duration windowLength = Duration.between(MetricWindow.windowStart(NOW), MetricWindow.windowEnd(NOW));

		assertThat(windowLength).isEqualTo(Duration.ofSeconds(60));
	}

	@ParameterizedTest
	@CsvSource({ "0,true", "-59,true", "-299,true", "-300,true", "-301,false", "5,true", "6,false" })
	void acceptsOnlyEventsInsideTheWriteWindow(long offsetSeconds, boolean accepted) {
		Instant occurredAt = NOW.plusSeconds(offsetSeconds);

		assertThat(MetricWindow.isWithinAcceptWindow(occurredAt, NOW)).isEqualTo(accepted);
	}

}

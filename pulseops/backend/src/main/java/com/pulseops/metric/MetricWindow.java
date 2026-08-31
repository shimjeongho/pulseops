package com.pulseops.metric;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Metric의 시간 축을 정한다.
 * <p>
 * Event를 10초 Bucket에 넣고 최근 6개 Bucket을 하나의 창으로 본다. Bucket을 쓰면 Event 하나하나의
 * 시각을 남기지 않고도 최근 구간을 합산할 수 있다. 창을 60초로 잡은 것은 더 짧으면 표본이 부족해
 * 값이 튀고, 더 길면 이상이 시작된 시점이 뭉개지기 때문이다.
 * <p>
 * 기준 시각은 Event가 도착한 시각이 아니라 요청이 끝난 시각이다. Consumer가 밀리면 도착 시각으로
 * 나눈 Bucket은 실제로 서비스가 아팠던 구간과 어긋난다.
 * <p>
 * 쓰는 쪽과 읽는 쪽이 같은 경계를 써야 하므로 계산을 이 한 곳에 모은다. 현재 시각은 호출자가
 * 넘기며 이 Class가 스스로 읽지 않는다. 그래야 임의의 시각을 그대로 검증할 수 있다.
 */
public final class MetricWindow {

	public static final int BUCKET_SECONDS = 10;

	public static final int WINDOW_BUCKETS = 6;

	/**
	 * 이보다 오래된 Event는 집계하지 않는다. Bucket이 Redis에 남아 있는 시간과 맞춘 값이며, 이미
	 * 사라진 Bucket을 되살리면 어떤 창에서도 읽히지 않는 값만 남는다.
	 */
	private static final long ACCEPT_PAST_SECONDS = 300;

	/**
	 * 두 Application의 시계 차이를 이만큼 허용한다. 이보다 앞선 시각은 아직 일어나지 않은 일이므로
	 * 시계 이상이나 손상된 값으로 본다.
	 */
	private static final long ACCEPT_FUTURE_SECONDS = 5;

	private MetricWindow() {
	}

	/**
	 * 시각이 속한 Bucket의 시작이다. Bucket의 범위는 시작 시각을 포함하고 10초 뒤를 포함하지 않는다.
	 * <p>
	 * 나눗셈 대신 {@link Math#floorDiv}를 쓴다. {@code /}는 음수에서 0 쪽으로 잘리므로 1970년 이전
	 * 시각에서 경계가 한 칸 뒤집힌다.
	 */
	public static Instant bucketStartOf(Instant occurredAt) {
		long bucketStartEpochSecond = Math.floorDiv(occurredAt.getEpochSecond(), BUCKET_SECONDS) * BUCKET_SECONDS;
		return Instant.ofEpochSecond(bucketStartEpochSecond);
	}

	/** 창의 시작이며 이 시각을 포함한다. */
	public static Instant windowStart(Instant now) {
		return windowEnd(now).minusSeconds((long) BUCKET_SECONDS * WINDOW_BUCKETS);
	}

	/**
	 * 창의 끝이며 이 시각을 포함하지 않는다. 지금 채워지는 중인 Bucket도 창에 들어와야 하므로 현재
	 * 시각이 아니라 그 Bucket의 끝이다.
	 */
	public static Instant windowEnd(Instant now) {
		return bucketStartOf(now).plusSeconds(BUCKET_SECONDS);
	}

	/**
	 * 창에 속한 Bucket 시작을 오래된 것부터 반환한다. 마지막 Bucket은 아직 채워지는 중이므로 창을
	 * 읽은 결과의 마지막 10초는 언제나 일부만 반영된 값이다.
	 */
	public static List<Instant> bucketStartsFor(Instant now) {
		Instant windowStart = windowStart(now);
		return IntStream.range(0, WINDOW_BUCKETS)
			.mapToObj((bucketIndex) -> windowStart.plusSeconds((long) bucketIndex * BUCKET_SECONDS))
			.toList();
	}

	/** 집계에 반영할 시각인지 판단한다. 양쪽 경계를 포함한다. */
	public static boolean isWithinAcceptWindow(Instant occurredAt, Instant now) {
		return !occurredAt.isBefore(now.minusSeconds(ACCEPT_PAST_SECONDS))
				&& !occurredAt.isAfter(now.plusSeconds(ACCEPT_FUTURE_SECONDS));
	}

}

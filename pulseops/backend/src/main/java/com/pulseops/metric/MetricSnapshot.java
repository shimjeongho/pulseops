package com.pulseops.metric;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 한 계열의 최근 창을 요약한 결과이다.
 * <p>
 * 이 결과를 읽는 쪽은 Bucket을 몇 개 합쳤는지, 어디에 어떻게 저장돼 있었는지 몰라도 된다.
 * <p>
 * requestCount가 0이면 errorRate는 0.0이다. 트래픽이 없는 것과 오류가 없는 것은 분명히 다른
 * 상태이지만 그 구분은 requestCount에 그대로 남아 있으므로, 나눗셈 결과를 null로 만들어 읽는 쪽에
 * null 처리를 강요하지 않는다. 반면 표본이 없을 때의 P95는 대신 쓸 수 있는 값이 없어 null이다. 0은
 * "0ms에 응답했다"는 거짓이고 -1 같은 표식은 다른 값들 사이에서 값처럼 읽힌다.
 * <p>
 * errorRate는 0~100의 백분율이며 반올림하지 않는다. 자리수를 정하는 것은 보여 주는 쪽의 몫이다.
 */
public record MetricSnapshot(String service, String method, String endpoint, Instant windowStart, Instant windowEnd,
		long requestCount, long errorCount, double errorRate, Long p95LatencyMs) {

	public static MetricSnapshot of(MetricSeries series, Instant windowStart, Instant windowEnd,
			MetricWindowData data) {
		return new MetricSnapshot(series.service(), series.method(), series.endpoint(), windowStart, windowEnd,
				data.requestCount(), data.errorCount(), errorRate(data.requestCount(), data.errorCount()),
				p95LatencyMs(data.latencySamples()));
	}

	private static double errorRate(long requestCount, long errorCount) {
		if (requestCount == 0) {
			return 0.0;
		}
		return (double) errorCount / requestCount * 100.0;
	}

	/**
	 * Nearest Rank로 P95를 구한다. 보간하지 않으므로 결과는 실제로 관측된 응답 시간 중 하나이며,
	 * 나중에 Incident를 설명할 때 그 시간이 걸린 요청이 실제로 존재한다.
	 * <p>
	 * 순위를 {@code Math.ceil(0.95 * n)}으로 구하지 않는다. n=20에서 {@code 0.95 * 20}이
	 * 19.000000000000004가 되어 순위가 20으로 올라가고 색인이 표본 범위를 벗어난다.
	 * {@code (95n + 99) / 100}은 정수 나눗셈으로 같은 올림을 계산하므로 이 경로가 아예 없다.
	 * <p>
	 * 색인은 항상 0 이상 n-1 이하라서 따로 잘라 내지 않는다. n이 1 이상이면 올림 결과가 1 이상이고,
	 * 0.95n은 n을 넘지 않기 때문이다.
	 * <p>
	 * 정렬 전에 복사한다. 넘겨받은 표본의 순서를 바꾸면 호출자가 들고 있는 값이 함께 바뀐다.
	 */
	private static Long p95LatencyMs(List<Long> latencySamples) {
		if (latencySamples.isEmpty()) {
			return null;
		}
		List<Long> sortedSamples = new ArrayList<>(latencySamples);
		Collections.sort(sortedSamples);
		int rank = (int) ((95L * sortedSamples.size() + 99) / 100);
		return sortedSamples.get(rank - 1);
	}

}

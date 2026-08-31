package com.pulseops.metric;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 한 계열의 최근 60초를 하나의 Snapshot으로 만든다.
 * <p>
 * 하는 일은 세 계층을 잇는 것뿐이다. 창의 경계는 {@link MetricWindow}가, 저장된 값을 읽어 오는 일은
 * {@link RedisMetricStore}가, Error Rate와 P95 계산은 {@link MetricSnapshot}이 맡는다. 그 중 어느
 * 것도 여기서 다시 계산하지 않는다. 같은 계산이 두 곳에 있으면 한쪽만 고쳐졌을 때 창을 읽는 값과
 * 쓰는 값이 조용히 어긋난다.
 * <p>
 * Redis Key도 자료 구조도 알지 못한다. Bucket 시작 시각까지만 정해 저장소에 넘긴다.
 * <p>
 * Redis 장애를 잡지 않는다. 여기서 삼키면 읽는 쪽이 "요청이 없었다"와 "읽지 못했다"를 구분할 수
 * 없다. Metric을 쓰는 쪽이 장애를 흡수하는 것은 Kafka 소비를 멈추지 않기 위한 것이라, 요청에 답하는
 * 읽기 경로에 그대로 옮겨 오지 않는다.
 */
@Component
public class RollingMetricService {

	private final RedisMetricStore metricStore;

	public RollingMetricService(RedisMetricStore metricStore) {
		this.metricStore = metricStore;
	}

	/**
	 * 기준 시각이 속한 Bucket까지 포함해 그 직전 60초를 요약한다. 현재 시각은 호출자가 넘긴다. 스스로
	 * 읽으면 Snapshot이 어느 창을 뜻하는지 테스트가 정할 수 없다.
	 */
	public MetricSnapshot snapshot(MetricSeries series, Instant now) {
		List<Instant> bucketStarts = MetricWindow.bucketStartsFor(now);
		MetricWindowData windowData = this.metricStore.read(series, bucketStarts);
		return MetricSnapshot.of(series, MetricWindow.windowStart(now), MetricWindow.windowEnd(now), windowData);
	}

}

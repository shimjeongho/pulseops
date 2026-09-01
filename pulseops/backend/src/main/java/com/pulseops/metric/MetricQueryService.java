package com.pulseops.metric;

import com.pulseops.metric.dto.MetricListResponse;
import com.pulseops.metric.dto.MetricSummaryResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 지금 관측 중인 모든 계열의 현재 창을 한 번에 모은다.
 * <p>
 * 하는 일은 계열을 찾아 각각의 Snapshot을 만들고 순서를 정하는 것뿐이다. 어떤 계열이 있는지는
 * {@link RedisMetricStore}가, 창의 값은 {@link RollingMetricService}가, Error Rate의 자리수는
 * {@link MetricSummaryResponse}가 안다. 그 중 어느 것도 여기서 다시 계산하지 않는다.
 * <p>
 * 판정에 쓰는 창이 {@link com.pulseops.incident.IncidentDetector}와 다르다. Detector는 마지막으로
 * 닫힌 창을 보지만 Dashboard는 "지금 어떤가"에 답해야 하므로, 채워지는 중인 Bucket을 포함한 현재
 * 창을 그대로 읽는다. 그래서 Detector가 판정에 쓴 값과 이 화면의 값은 다를 수 있으며, 그 차이를
 * 감추지 않기 위해 응답에 창의 경계를 함께 담는다.
 * <p>
 * Redis 실패를 빈 목록으로 바꾸지 않는다. 그렇게 하면 저장소 장애가 "관측 중인 API 없음"과 같은
 * 화면이 되어, 아무 일도 없는 것처럼 보인다. 일부 계열만 담아 돌려주지도 않는다. 어떤 계열이 빠졌는지
 * 알 수 없는 목록은 오히려 잘못된 판단의 근거가 된다.
 */
@Service
public class MetricQueryService {

	/**
	 * 값이 아니라 계열의 이름으로 순서를 정한다. Error Rate나 요청 수로 정렬하면 몇 초마다 다시 읽을
	 * 때 줄의 위치가 계속 바뀌어, 보고 있던 API를 눈으로 따라갈 수 없다. Redis가 돌려준 순서에도
	 * 기대지 않는다. 그 순서는 score와 삽입 시점이 정하는 것이라 화면의 기준이 될 수 없다.
	 */
	private static final Comparator<MetricSummaryResponse> SERIES_ORDER = Comparator
		.comparing(MetricSummaryResponse::service)
		.thenComparing(MetricSummaryResponse::endpoint)
		.thenComparing(MetricSummaryResponse::method);

	private final RedisMetricStore metricStore;

	private final RollingMetricService rollingMetricService;

	public MetricQueryService(RedisMetricStore metricStore, RollingMetricService rollingMetricService) {
		this.metricStore = metricStore;
		this.rollingMetricService = rollingMetricService;
	}

	/**
	 * 현재 시각을 읽는 자리는 이 한 곳뿐이다. 계열을 고르는 기준과 창을 자르는 기준, 그리고 응답에
	 * 적히는 시각이 모두 같은 순간이어야 한 응답이 하나의 장면을 뜻한다.
	 */
	public MetricListResponse currentMetrics() {
		return currentMetrics(Instant.now());
	}

	MetricListResponse currentMetrics(Instant now) {
		try {
			List<MetricSummaryResponse> items = this.metricStore.activeSeries(now)
				.stream()
				.map((series) -> MetricSummaryResponse.from(this.rollingMetricService.snapshot(series, now)))
				.sorted(SERIES_ORDER)
				.toList();
			return new MetricListResponse(now, items);
		}
		// Redis 실패만 옮긴다. 이 자리에서 실패할 수 있는 저장소는 Redis뿐이고, Type을 좁혀 두면
		// 나중에 다른 저장소를 읽게 되더라도 그 실패가 Metric 장애로 잘못 설명되지 않는다.
		catch (DataAccessException ex) {
			throw new MetricStoreUnavailableException(ex);
		}
	}

}

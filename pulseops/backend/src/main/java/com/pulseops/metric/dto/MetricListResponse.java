package com.pulseops.metric.dto;

import java.time.Instant;
import java.util.List;

/**
 * Metric 조회 응답 전체이다.
 * <p>
 * generatedAt은 이 응답의 모든 계열이 공유하는 기준 시각이다. 계열마다 시각을 다시 읽으면 한 화면
 * 안의 줄들이 서로 다른 순간을 가리키게 된다.
 * <p>
 * items는 비어 있을 수 있지만 null일 수 없다. 관측 중인 계열이 없는 것은 정상이며, 그 상태와 저장소
 * 장애를 같은 모양으로 만들지 않기 위해 빈 목록과 오류 응답을 분명히 나눈다.
 */
public record MetricListResponse(Instant generatedAt, List<MetricSummaryResponse> items) {

	public MetricListResponse {
		items = List.copyOf(items);
	}

}

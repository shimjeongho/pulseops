package com.pulseops.metric;

import java.util.List;

/**
 * 창 하나에서 읽어 온 원본 집계값이다.
 * <p>
 * 저장소가 돌려준 숫자만 담고 파생 값은 계산하지 않는다. Error Rate와 P95는 {@link MetricSnapshot}이
 * 계산하며, 그 계산의 입력을 이렇게 Type으로 분리해 두면 저장소 없이도 계산 규칙을 검증할 수 있다.
 * <p>
 * latencySamples는 정렬되어 있다고 보지 않는다. 여러 Bucket에서 읽은 표본을 이어 붙인 결과이기
 * 때문이다. 넘겨받은 List를 그대로 들고 있으면 호출자가 나중에 고칠 때 값 객체가 함께 바뀌므로
 * 복사해서 보관한다.
 */
public record MetricWindowData(long requestCount, long errorCount, List<Long> latencySamples) {

	public MetricWindowData {
		latencySamples = List.copyOf(latencySamples);
	}

}

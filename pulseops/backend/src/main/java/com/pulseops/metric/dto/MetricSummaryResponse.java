package com.pulseops.metric.dto;

import com.pulseops.metric.MetricSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 한 계열의 현재 창을 Dashboard에 보여 주기 위한 형태이다.
 * <p>
 * windowStart와 windowEnd를 함께 내보낸다. 이 값들이 어느 구간을 요약한 것인지 응답만 보고 알 수
 * 있어야 하며, Incident에 남은 판정 창과는 다른 구간이라 더더욱 생략할 수 없다.
 * <p>
 * requestCount가 0인 계열도 그대로 내보낸다. 트래픽이 끊긴 것은 감출 일이 아니라 보여 줄 상태이다.
 */
public record MetricSummaryResponse(String service, String method, String endpoint, Instant windowStart,
		Instant windowEnd, long requestCount, long errorCount, BigDecimal errorRate, Long p95LatencyMs) {

	/**
	 * Incident의 {@code trigger_error_rate}가 소수 둘째 자리까지이므로 같은 자리수로 맞춘다. 두 API가
	 * 다른 자리수를 쓰면 같은 순간의 같은 오류율이 화면에서 서로 다른 숫자로 보인다.
	 */
	private static final int ERROR_RATE_SCALE = 2;

	/**
	 * {@link MetricSnapshot#errorRate()}는 반올림하지 않은 값이라 그대로 내보내면 나눗셈의 나머지가
	 * 그대로 드러난다. 자리수를 정하는 것은 보여 주는 쪽의 몫이라는 Snapshot의 계약을 여기서 지킨다.
	 * <p>
	 * P95는 표본이 없으면 null이며 0으로 바꾸지 않는다. 응답 시간을 재지 못한 창이 가장 빠른 창이 된다.
	 */
	public static MetricSummaryResponse from(MetricSnapshot snapshot) {
		return new MetricSummaryResponse(snapshot.service(), snapshot.method(), snapshot.endpoint(),
				snapshot.windowStart(), snapshot.windowEnd(), snapshot.requestCount(), snapshot.errorCount(),
				BigDecimal.valueOf(snapshot.errorRate()).setScale(ERROR_RATE_SCALE, RoundingMode.HALF_UP),
				snapshot.p95LatencyMs());
	}

}

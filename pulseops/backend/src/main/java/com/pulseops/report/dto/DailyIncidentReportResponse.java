package com.pulseops.report.dto;

import com.pulseops.report.DailyIncidentReport;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Daily Incident Report 하나를 Client가 읽는 형태이다.
 * <p>
 * 집계 구간을 함께 내보낸다. 날짜만으로는 어느 시간대의 하루인지 알 수 없어, 읽는 쪽이 자기 시간대의
 * 하루로 오해할 수 있다. 두 시각이 있으면 이 숫자들이 정확히 어느 구간의 것인지 스스로 확인할 수 있다.
 * <p>
 * {@code generatedAt}도 담는다. 이 Report는 그 순간에 굳은 snapshot이며 이후 그 날짜의 Incident가
 * 확인되거나 해결되어도 값이 움직이지 않는다. 언제 굳었는지를 알아야 지금의 Incident 목록과 이 숫자가
 * 다를 때 그것이 오류가 아님을 알 수 있다.
 * <p>
 * id를 담지 않는다. 이 Report를 밖에서 가리키는 값은 날짜이며 REST 경로도 날짜를 쓴다. 저장소가 부여한
 * 번호는 밖에서 쓸 일이 없고, 내보내면 Client가 그것으로 Report를 가리키기 시작한다.
 * <p>
 * 해결 시간 통계는 그 날 해결된 Incident가 없으면 null이며 0으로 바꾸지 않는다. 아무것도 해결하지 못한
 * 하루가 모든 것을 즉시 해결한 하루로 보인다.
 */
public record DailyIncidentReportResponse(LocalDate reportDate, Instant periodStart, Instant periodEnd,
		long openedCount, long errorRateOpenedCount, long latencyOpenedCount, long acknowledgedCount,
		long resolvedCount, long unresolvedAtPeriodEndCount, Long averageResolutionSeconds, Long maxResolutionSeconds,
		Instant generatedAt) {

	public static DailyIncidentReportResponse from(DailyIncidentReport report) {
		return new DailyIncidentReportResponse(report.getReportDate(), report.getPeriodStart(), report.getPeriodEnd(),
				report.getOpenedCount(), report.getErrorRateOpenedCount(), report.getLatencyOpenedCount(),
				report.getAcknowledgedCount(), report.getResolvedCount(), report.getUnresolvedAtPeriodEndCount(),
				report.getAverageResolutionSeconds(), report.getMaxResolutionSeconds(), report.getGeneratedAt());
	}

}

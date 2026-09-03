package com.pulseops.report;

/**
 * 하루 구간에서 세어 낸 Incident 운영 현황이다. Report에 담기기 전의 순수한 집계 결과이며 저장을
 * 알지 못한다.
 * <p>
 * 세 가지 lifecycle Event를 각각 그 일이 일어난 날짜로 센다. 하나의 Incident가 2일에 열려 3일에
 * 확인되고 4일에 해결됐다면 세 날짜의 Report에 각각 한 번씩 등장한다. 어느 한 날짜에 몰아 세면
 * "그 날 무슨 일이 있었는가"를 물을 수 없다.
 * <p>
 * 심각도별 건수를 담지 않는다. Incident는 지금의 심각도만 보관하고 열렸을 때의 심각도를 따로 남기지
 * 않으므로, 여기서 셀 수 있는 것은 "CRITICAL로 열린 건수"가 아니라 "Report를 만드는 순간 CRITICAL인
 * 건수"뿐이다. 그 값은 아직 처리 중인 Incident가 나중에 악화되면 사실과 달라지는데 이 Report는 다시
 * 만들어지지 않는다. 이름이 뜻하는 것과 값이 뜻하는 것이 다른 숫자를 남기느니 담지 않는다. 대신
 * 종류별 건수를 담는다. 종류는 Incident가 열릴 때 정해진 뒤 바뀌지 않아 뜻이 흔들리지 않는다.
 */
public record DailyIncidentAggregate(long openedCount, long errorRateOpenedCount, long latencyOpenedCount,
		long acknowledgedCount, long resolvedCount, long unresolvedAtPeriodEndCount, Long averageResolutionSeconds,
		Long maxResolutionSeconds) {

	/**
	 * 해결 시간 통계는 그 날 해결된 Incident가 있을 때에만 있고 없을 때에는 없다. 0으로 채우면 아무도
	 * 해결하지 않은 하루가 모든 것을 즉시 해결한 하루처럼 보인다. 반대로 해결된 Incident가 있는데
	 * 통계가 비어 있으면 집계가 도중에 빠진 것이므로, 두 방향을 모두 막는다.
	 */
	public DailyIncidentAggregate {
		requireNotNegative(openedCount, "openedCount");
		requireNotNegative(errorRateOpenedCount, "errorRateOpenedCount");
		requireNotNegative(latencyOpenedCount, "latencyOpenedCount");
		requireNotNegative(acknowledgedCount, "acknowledgedCount");
		requireNotNegative(resolvedCount, "resolvedCount");
		requireNotNegative(unresolvedAtPeriodEndCount, "unresolvedAtPeriodEndCount");
		boolean hasResolutionStatistics = averageResolutionSeconds != null && maxResolutionSeconds != null;
		if (hasResolutionStatistics != (resolvedCount > 0)) {
			throw new IllegalArgumentException(
					"해결 시간 통계는 해결된 Incident가 있을 때에만 존재해야 한다: resolvedCount=" + resolvedCount);
		}
	}

	private static void requireNotNegative(long count, String name) {
		if (count < 0) {
			throw new IllegalArgumentException("건수는 음수일 수 없다: " + name + "=" + count);
		}
	}

}

package com.pulseops.report;

import java.time.LocalDate;

/**
 * 요청한 날짜의 Daily Incident Report가 없을 때 발생한다.
 * <p>
 * 아직 Scheduler가 만들지 않은 날짜, Scheduler가 돌지 못한 날짜, 서비스를 시작하기도 전인 날짜가
 * 모두 같은 결과이다. 셋을 나누려면 어느 날짜까지 Report가 있어야 하는지를 알아야 하는데, 그것은
 * 지금 어디에도 기록되어 있지 않다. 없는 근거로 나눈 답을 내보내지 않는다.
 */
public class DailyIncidentReportNotFoundException extends RuntimeException {

	public DailyIncidentReportNotFoundException(LocalDate reportDate) {
		super("Daily incident report not found: " + reportDate);
	}

}

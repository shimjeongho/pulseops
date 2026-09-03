package com.pulseops.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Daily Report가 하루를 어디서 자르는지 고정한다.
 * <p>
 * 이 경계가 이 Phase의 계약이다. 하루의 시작을 포함하고 끝을 포함하지 않는다는 규칙이 흔들리면
 * 자정 정각에 일어난 일이 이틀치 Report에 모두 세어지거나 어느 쪽에도 세어지지 않는다.
 */
class DailyIncidentReportPeriodTest {

	private static final LocalDate REPORT_DATE = LocalDate.of(2026, 9, 3);

	@Test
	void startsAtTheUtcMidnightOfTheReportDate() {
		assertThat(new DailyIncidentReportPeriod(REPORT_DATE).start()).isEqualTo(Instant.parse("2026-09-03T00:00:00Z"));
	}

	@Test
	void endsAtTheUtcMidnightOfTheNextDate() {
		assertThat(new DailyIncidentReportPeriod(REPORT_DATE).end()).isEqualTo(Instant.parse("2026-09-04T00:00:00Z"));
	}

	/** 하루가 24시간이라고 계산하지 않고 다음 날 자정을 구하므로 월과 해가 바뀌는 자리도 같다. */
	@Test
	void crossesTheYearBoundaryWithoutAssumingAFixedDayLength() {
		DailyIncidentReportPeriod period = new DailyIncidentReportPeriod(LocalDate.of(2026, 12, 31));

		assertThat(period.start()).isEqualTo(Instant.parse("2026-12-31T00:00:00Z"));
		assertThat(period.end()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
	}

	/** 하루의 마지막 순간은 다음 날 자정 직전까지이다. */
	@Test
	void includesTheLastInstantBeforeTheNextMidnight() {
		DailyIncidentReportPeriod period = new DailyIncidentReportPeriod(REPORT_DATE);

		assertThat(Instant.parse("2026-09-03T23:59:59.999999Z")).isBefore(period.end());
	}

	@Test
	void treatsTheDayBeforeTheGivenInstantAsTheLastCompletedDate() {
		assertThat(DailyIncidentReportPeriod.lastCompletedDate(Instant.parse("2026-09-04T00:05:00Z")))
			.isEqualTo(REPORT_DATE);
	}

	/** 자정 정각은 이미 다음 날이므로 그 직전 날짜가 마지막으로 끝난 날짜이다. */
	@Test
	void treatsMidnightAsTheFirstInstantOfANewDate() {
		assertThat(DailyIncidentReportPeriod.lastCompletedDate(Instant.parse("2026-09-04T00:00:00Z")))
			.isEqualTo(REPORT_DATE);
		assertThat(DailyIncidentReportPeriod.lastCompletedDate(Instant.parse("2026-09-03T23:59:59.999999Z")))
			.isEqualTo(LocalDate.of(2026, 9, 2));
	}

	/** 실행 시각이 하루 중 언제든 대상은 언제나 완전히 끝난 전날이다. */
	@Test
	void neverTargetsTheDateThatIsStillRunning() {
		assertThat(DailyIncidentReportPeriod.lastCompletedDate(Instant.parse("2026-09-04T18:42:13Z")))
			.isEqualTo(REPORT_DATE);
	}

}

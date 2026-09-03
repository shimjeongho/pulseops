package com.pulseops.report;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Scheduler가 무엇을 하고 무엇을 하지 않는지 고정한다.
 * <p>
 * 이 Class가 확인하는 것은 집계 결과가 아니라 위임이다. Scheduler가 스스로 세기 시작하면 그 계산을
 * 확인하려면 시간이 지나기를 기다려야 하고, 어느 날짜를 대상으로 삼는지도 함께 검증하기 어려워진다.
 * <p>
 * 시각을 받는 method를 부른다. cron이 실제로 도는 것을 기다리지 않고도 날짜 계산을 확정할 수 있으며,
 * 그러려고 production의 cron을 몇 초마다로 바꾸지 않는다. 그렇게 바꾼 설정은 검증한 것과 실제로 도는
 * 것이 다른 상태를 만든다.
 */
class DailyIncidentReportSchedulerTest {

	private static final Instant SCHEDULED_AT = Instant.parse("2026-09-04T00:05:00Z");

	private static final LocalDate LAST_COMPLETED_DATE = LocalDate.of(2026, 9, 3);

	private final DailyIncidentReportService dailyIncidentReportService = mock(DailyIncidentReportService.class);

	private final DailyIncidentReportScheduler scheduler = new DailyIncidentReportScheduler(
			this.dailyIncidentReportService);

	/** 실행 시각이 아니라 그 직전에 끝난 날짜를 정확히 한 번 넘긴다. */
	@Test
	void asksForTheLastCompletedDateExactlyOnce() {
		given(this.dailyIncidentReportService.generate(any(), any())).willReturn(created());

		this.scheduler.generateLastCompletedDateReport(SCHEDULED_AT);

		then(this.dailyIncidentReportService).should(only()).generate(LAST_COMPLETED_DATE, SCHEDULED_AT);
	}

	/** 집계 기준 시각과 기록 시각은 같은 순간이어야 하므로 실행 시각을 그대로 넘긴다. */
	@Test
	void passesTheSameInstantAsTheGenerationTime() {
		given(this.dailyIncidentReportService.generate(any(), any())).willReturn(created());

		this.scheduler.generateLastCompletedDateReport(SCHEDULED_AT);

		then(this.dailyIncidentReportService).should().generate(eq(LAST_COMPLETED_DATE), eq(SCHEDULED_AT));
	}

	/** 이미 있는 날짜를 다시 만나도 실패가 아니다. Scheduler는 그 사실만 남기고 정상적으로 끝난다. */
	@Test
	void finishesNormallyWhenTheReportAlreadyExists() {
		given(this.dailyIncidentReportService.generate(any(), any())).willReturn(existing());

		assertThatCode(() -> this.scheduler.generateLastCompletedDateReport(SCHEDULED_AT)).doesNotThrowAnyException();
	}

	/**
	 * 저장소가 실패해도 Scheduler 밖으로 예외를 내보내지 않는다. 다음 실행이 이어져야 하고, 무엇을
	 * 하려다 실패했는지는 log에 남는다.
	 */
	@Test
	void doesNotPropagateAGenerationFailure() {
		willThrow(new DataAccessResourceFailureException("db down")).given(this.dailyIncidentReportService)
			.generate(any(), any());

		assertThatCode(() -> this.scheduler.generateLastCompletedDateReport(SCHEDULED_AT)).doesNotThrowAnyException();
	}

	/** 한 번 실패했다고 그 다음 실행이 멈추지 않는다. */
	@Test
	void keepsRunningAfterAFailedExecution() {
		willThrow(new DataAccessResourceFailureException("db down")).given(this.dailyIncidentReportService)
			.generate(eq(LAST_COMPLETED_DATE), any());

		this.scheduler.generateLastCompletedDateReport(SCHEDULED_AT);

		given(this.dailyIncidentReportService.generate(any(), any())).willReturn(created());
		this.scheduler.generateLastCompletedDateReport(Instant.parse("2026-09-05T00:05:00Z"));

		then(this.dailyIncidentReportService).should().generate(LocalDate.of(2026, 9, 4),
				Instant.parse("2026-09-05T00:05:00Z"));
	}

	private static DailyIncidentReportGenerationResult created() {
		return new DailyIncidentReportGenerationResult(report(), true);
	}

	private static DailyIncidentReportGenerationResult existing() {
		return new DailyIncidentReportGenerationResult(report(), false);
	}

	private static DailyIncidentReport report() {
		return DailyIncidentReport.of(new DailyIncidentReportPeriod(LAST_COMPLETED_DATE),
				new DailyIncidentAggregate(0, 0, 0, 0, 0, 0, null, null), SCHEDULED_AT);
	}

}

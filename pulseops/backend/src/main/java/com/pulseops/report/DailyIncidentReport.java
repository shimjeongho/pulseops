package com.pulseops.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 완전히 끝난 하루의 Incident 운영 현황을 집계해 남긴 기록이다.
 * <p>
 * 만들어진 뒤에는 바뀌지 않는다. 상태를 바꾸는 method가 하나도 없는 것이 그 뜻이다. 이 기록은
 * {@code generatedAt} 시점에 Incident가 어떤 모습이었는지를 그대로 굳힌 것이며, 이후 그 날짜의
 * Incident가 확인되거나 해결되어도 여기의 숫자는 움직이지 않는다. 움직이게 만들면 어제 본 Report와
 * 오늘 보는 Report가 달라져, 어느 쪽을 근거로 이야기했는지 아무도 말할 수 없게 된다. 대신 그 뒤에
 * 일어난 lifecycle Event는 그 일이 일어난 날짜의 Report에 세어진다.
 * <p>
 * Incident를 참조하지 않는다. 이 기록은 그 날짜의 집계 결과이지 어떤 Incident의 소유자가 아니다.
 * 어떤 Incident가 세어졌는지는 period로 다시 조회하면 알 수 있고, 그 목록을 여기에 복사해 두면
 * Incident가 바뀔 때 두 값이 어긋난다.
 * <p>
 * 집계 자체는 하지 않는다. 무엇을 어떻게 세는지는 {@link DailyIncidentReportService}가 알고, 여기는
 * 다 센 결과를 담는다. 집계와 저장을 한 Class에 두면 Report를 만들지 않고 숫자만 확인할 방법이 없어진다.
 * <p>
 * Schema는 Flyway가 관리한다. 같은 날짜의 Report가 하나뿐인 것은 {@code report_date}의 UNIQUE가
 * 최종적으로 보장하며, 그것이 Scheduler를 몇 번 돌려도 안전한 근거이다.
 */
@Entity
@Table(name = "daily_incident_report")
public class DailyIncidentReport {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "report_date", nullable = false)
	private LocalDate reportDate;

	@Column(name = "period_start", nullable = false)
	private Instant periodStart;

	@Column(name = "period_end", nullable = false)
	private Instant periodEnd;

	@Column(name = "opened_count", nullable = false)
	private long openedCount;

	@Column(name = "error_rate_opened_count", nullable = false)
	private long errorRateOpenedCount;

	@Column(name = "latency_opened_count", nullable = false)
	private long latencyOpenedCount;

	@Column(name = "acknowledged_count", nullable = false)
	private long acknowledgedCount;

	@Column(name = "resolved_count", nullable = false)
	private long resolvedCount;

	@Column(name = "unresolved_at_period_end_count", nullable = false)
	private long unresolvedAtPeriodEndCount;

	/** 그 날짜에 해결된 Incident가 없으면 값이 없다. 0은 "즉시 해결"이라는 다른 뜻이다. */
	@Column(name = "average_resolution_seconds")
	private Long averageResolutionSeconds;

	@Column(name = "max_resolution_seconds")
	private Long maxResolutionSeconds;

	@Column(name = "generated_at", nullable = false)
	private Instant generatedAt;

	protected DailyIncidentReport() {
		// JPA 전용 기본 생성자
	}

	/**
	 * 집계 결과를 Report로 굳힌다.
	 * <p>
	 * 건수를 낱개로 받지 않고 {@link DailyIncidentAggregate}를 통째로 받는다. 여덟 개의 수를 늘어놓으면
	 * 전부 같은 Type이라 순서가 뒤바뀌어도 compile되고, 열린 건수와 해결된 건수가 조용히 뒤집힌
	 * Report가 남는다. 값이 서로 모순되지 않는지는 그 record가 만들어질 때 이미 확인되므로 여기서
	 * 다시 검사하지 않는다.
	 * <p>
	 * 구간도 {@link DailyIncidentReportPeriod}에서 받는다. 그 record는 날짜에서 두 끝을 파생시키므로
	 * 날짜와 어긋난 구간이 들어올 수 없고, 시작이 끝보다 뒤인 경우도 존재하지 않는다.
	 * <p>
	 * {@code generatedAt}은 호출자가 넘긴다. 여기서 시계를 읽으면 한 번의 Scheduler 실행 안에서
	 * 집계 기준 시각과 기록 시각이 미세하게 달라지고, 그 시각을 테스트가 정할 수 없게 된다.
	 */
	public static DailyIncidentReport of(DailyIncidentReportPeriod period, DailyIncidentAggregate aggregate,
			Instant generatedAt) {
		DailyIncidentReport report = new DailyIncidentReport();
		report.reportDate = period.reportDate();
		report.periodStart = period.start();
		report.periodEnd = period.end();
		report.openedCount = aggregate.openedCount();
		report.errorRateOpenedCount = aggregate.errorRateOpenedCount();
		report.latencyOpenedCount = aggregate.latencyOpenedCount();
		report.acknowledgedCount = aggregate.acknowledgedCount();
		report.resolvedCount = aggregate.resolvedCount();
		report.unresolvedAtPeriodEndCount = aggregate.unresolvedAtPeriodEndCount();
		report.averageResolutionSeconds = aggregate.averageResolutionSeconds();
		report.maxResolutionSeconds = aggregate.maxResolutionSeconds();
		report.generatedAt = generatedAt;
		return report;
	}

	public Long getId() {
		return this.id;
	}

	public LocalDate getReportDate() {
		return this.reportDate;
	}

	public Instant getPeriodStart() {
		return this.periodStart;
	}

	public Instant getPeriodEnd() {
		return this.periodEnd;
	}

	public long getOpenedCount() {
		return this.openedCount;
	}

	public long getErrorRateOpenedCount() {
		return this.errorRateOpenedCount;
	}

	public long getLatencyOpenedCount() {
		return this.latencyOpenedCount;
	}

	public long getAcknowledgedCount() {
		return this.acknowledgedCount;
	}

	public long getResolvedCount() {
		return this.resolvedCount;
	}

	public long getUnresolvedAtPeriodEndCount() {
		return this.unresolvedAtPeriodEndCount;
	}

	public Long getAverageResolutionSeconds() {
		return this.averageResolutionSeconds;
	}

	public Long getMaxResolutionSeconds() {
		return this.maxResolutionSeconds;
	}

	public Instant getGeneratedAt() {
		return this.generatedAt;
	}

}

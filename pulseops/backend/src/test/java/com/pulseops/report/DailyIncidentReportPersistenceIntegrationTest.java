package com.pulseops.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.pulseops.MySqlTestcontainersConfiguration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Daily Incident Report가 실제 MySQL에 어떤 값으로 저장되고 어떤 값으로 돌아오는지 검증한다.
 * <p>
 * Context가 뜨는 것 자체가 첫 번째 검증이다. {@code ddl-auto=validate}가 Flyway로 만든 table과 Entity
 * mapping을 대조하므로, 열 이름이나 Type, null 허용이 어긋나면 이 Class의 모든 테스트가 기동 단계에서
 * 실패한다.
 * <p>
 * 같은 날짜를 두 번 저장할 수 없다는 것을 여기서 고정한다. Scheduler를 몇 번 돌려도 Report가 하나뿐인
 * 근거가 결국 이 제약이며, Application의 존재 확인은 그보다 앞선 편의일 뿐이다.
 * <p>
 * 저장된 값은 Java에서 읽은 것만 보지 않고 SQL로도 확인한다. 시각이 시간대 변환 없이 UTC로 들어갔는지는
 * Java 쪽에서 왕복시켜 보면 두 번의 변환이 서로를 가려 통과해 버린다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class DailyIncidentReportPersistenceIntegrationTest {

	private static final LocalDate REPORT_DATE = LocalDate.of(2026, 9, 3);

	/** DATETIME(6)까지 남는지 보려고 마이크로초 자리를 채운다. */
	private static final Instant GENERATED_AT = Instant.parse("2026-09-04T00:05:00.123456Z");

	@Autowired
	private DailyIncidentReportRepository dailyIncidentReportRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void removeReports() {
		this.dailyIncidentReportRepository.deleteAll();
	}

	@Test
	void storesAndReloadsEveryField() {
		Long id = this.dailyIncidentReportRepository.saveAndFlush(report(REPORT_DATE, aggregate())).getId();

		DailyIncidentReport reloaded = this.dailyIncidentReportRepository.findById(id).orElseThrow();

		assertThat(reloaded.getReportDate()).isEqualTo(REPORT_DATE);
		assertThat(reloaded.getPeriodStart()).isEqualTo(Instant.parse("2026-09-03T00:00:00Z"));
		assertThat(reloaded.getPeriodEnd()).isEqualTo(Instant.parse("2026-09-04T00:00:00Z"));
		assertThat(reloaded.getOpenedCount()).isEqualTo(5);
		assertThat(reloaded.getErrorRateOpenedCount()).isEqualTo(3);
		assertThat(reloaded.getLatencyOpenedCount()).isEqualTo(2);
		assertThat(reloaded.getAcknowledgedCount()).isEqualTo(4);
		assertThat(reloaded.getResolvedCount()).isEqualTo(6);
		assertThat(reloaded.getUnresolvedAtPeriodEndCount()).isEqualTo(7);
		assertThat(reloaded.getAverageResolutionSeconds()).isEqualTo(120L);
		assertThat(reloaded.getMaxResolutionSeconds()).isEqualTo(300L);
		assertThat(reloaded.getGeneratedAt()).isEqualTo(GENERATED_AT);
	}

	/** 날짜는 시각이 아니라 달력의 하루로 저장된다. 시각으로 저장하면 시간대에 따라 날짜가 흔들린다. */
	@Test
	void storesTheReportDateAsACalendarDate() {
		this.dailyIncidentReportRepository.saveAndFlush(report(REPORT_DATE, aggregate()));

		assertThat(this.jdbcTemplate.queryForObject("select report_date from daily_incident_report", String.class))
			.isEqualTo("2026-09-03");
	}

	/** 시각은 JVM 시간대를 타지 않고 UTC 그대로 들어간다. */
	@Test
	void storesEveryInstantInUtc() {
		this.dailyIncidentReportRepository.saveAndFlush(report(REPORT_DATE, aggregate()));

		assertThat(this.jdbcTemplate.queryForObject(
				"select date_format(generated_at, '%Y-%m-%d %H:%i:%s.%f') from daily_incident_report", String.class))
			.isEqualTo("2026-09-04 00:05:00.123456");
		assertThat(this.jdbcTemplate.queryForObject(
				"select date_format(period_start, '%Y-%m-%d %H:%i:%s') from daily_incident_report", String.class))
			.isEqualTo("2026-09-03 00:00:00");
	}

	/** 해결된 Incident가 없던 날의 통계는 비어 있는 채로 저장되고 비어 있는 채로 돌아온다. */
	@Test
	void keepsTheResolutionStatisticsNullWhenNothingWasResolved() {
		Long id = this.dailyIncidentReportRepository
			.saveAndFlush(report(REPORT_DATE, new DailyIncidentAggregate(1, 1, 0, 0, 0, 1, null, null)))
			.getId();

		DailyIncidentReport reloaded = this.dailyIncidentReportRepository.findById(id).orElseThrow();

		assertThat(reloaded.getAverageResolutionSeconds()).isNull();
		assertThat(reloaded.getMaxResolutionSeconds()).isNull();
	}

	/**
	 * 같은 날짜의 Report는 하나뿐이다. Application이 존재 확인을 건너뛰더라도 DB가 막는다는 것이
	 * Scheduler 중복 실행에 대한 마지막 보장이다.
	 */
	@Test
	void refusesASecondReportForTheSameDate() {
		this.dailyIncidentReportRepository.saveAndFlush(report(REPORT_DATE, aggregate()));

		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> this.dailyIncidentReportRepository.saveAndFlush(report(REPORT_DATE, aggregate())));
	}

	/** 날짜가 다르면 서로 다른 행이다. */
	@Test
	void acceptsReportsForDifferentDates() {
		this.dailyIncidentReportRepository.saveAndFlush(report(REPORT_DATE, aggregate()));
		this.dailyIncidentReportRepository.saveAndFlush(report(REPORT_DATE.plusDays(1), aggregate()));

		assertThat(this.dailyIncidentReportRepository.count()).isEqualTo(2);
	}

	/** 최근 것이 먼저 오고, 요청한 개수까지만 온다. */
	@Test
	void readsTheMostRecentReportsFirstUpToTheRequestedCount() {
		for (int day = 1; day <= 5; day++) {
			this.dailyIncidentReportRepository.saveAndFlush(report(LocalDate.of(2026, 9, day), aggregate()));
		}

		List<DailyIncidentReport> reports = this.dailyIncidentReportRepository
			.findAllByOrderByReportDateDesc(Limit.of(3));

		assertThat(reports).extracting(DailyIncidentReport::getReportDate)
			.containsExactly(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 3));
	}

	private static DailyIncidentReport report(LocalDate reportDate, DailyIncidentAggregate aggregate) {
		return DailyIncidentReport.of(new DailyIncidentReportPeriod(reportDate), aggregate, GENERATED_AT);
	}

	/** 열마다 다른 값을 넣어 두 열이 서로 뒤바뀌어 저장되면 드러나게 한다. */
	private static DailyIncidentAggregate aggregate() {
		return new DailyIncidentAggregate(5, 3, 2, 4, 6, 7, 120L, 300L);
	}

}

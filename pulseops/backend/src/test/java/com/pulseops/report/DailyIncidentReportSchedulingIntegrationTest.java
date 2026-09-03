package com.pulseops.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.incident.Incident;
import com.pulseops.incident.IncidentRepository;
import com.pulseops.incident.IncidentSeverity;
import com.pulseops.incident.IncidentType;
import com.pulseops.metric.MetricSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제로 뜬 Application에 Daily Report 작업이 어떻게 등록되어 있는지, 그리고 그 등록된 작업을 그대로
 * 실행하면 무슨 일이 일어나는지 확인한다.
 * <p>
 * cron이 도는 것을 기다리지 않는다. 하루에 한 번 도는 작업을 기다리는 테스트는 만들 수 없고, 그렇다고
 * production의 cron을 몇 초마다로 바꾸면 검증한 것과 실제로 도는 것이 달라진다. 대신 Spring이 등록해
 * 둔 작업 자체를 꺼내 그 실행 내용을 직접 돌린다. 이렇게 하면 실행 주기는 등록된 값으로 확인하고,
 * 하는 일은 실제 MySQL 위에서 확인할 수 있다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class DailyIncidentReportSchedulingIntegrationTest {

	private static final String DAILY_REPORT_CRON = "0 5 0 * * *";

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private DailyIncidentReportRepository dailyIncidentReportRepository;

	@Autowired
	private IncidentRepository incidentRepository;

	@AfterEach
	void removeEverything() {
		this.dailyIncidentReportRepository.deleteAll();
		this.incidentRepository.deleteAll();
	}

	/** 이 Application에서 시각에 묶여 도는 일은 Daily Report 하나뿐이다. */
	@Test
	void registersTheDailyReportAsTheOnlyScheduledTask() {
		List<CronTask> tasks = cronTasks();

		assertThat(tasks).hasSize(1);
		assertThat(tasks.get(0).getExpression()).isEqualTo(DAILY_REPORT_CRON);
	}

	/**
	 * 실행 시각의 시간대는 Report가 하루를 자르는 기준과 같아야 한다. 둘이 갈라지면 아직 끝나지 않은
	 * 날짜를 집계하는 날이 생긴다.
	 */
	@Test
	void runsInTheSameZoneThatThePeriodUses() {
		assertThat(DailyIncidentReportPeriod.ZONE_ID).isEqualTo("UTC");
		assertThat(cronTasks().get(0).getTrigger())
			.isEqualTo(new CronTrigger(DAILY_REPORT_CRON, ZoneId.of(DailyIncidentReportPeriod.ZONE_ID)))
			.isNotEqualTo(new CronTrigger(DAILY_REPORT_CRON, ZoneId.of("Asia/Seoul")));
	}

	/**
	 * SSE 연결 신호는 이 Scheduler를 쓰지 않는다. 같은 Scheduler를 나눠 쓰면 하루치를 집계하는 일이
	 * 오래 걸리는 날 그 신호가 함께 밀린다.
	 */
	@Test
	void keepsTheSseExecutorSeparateFromTheScheduler() {
		assertThat(this.applicationContext.getBean(TaskScheduler.class)).isNotNull();
		assertThat(this.applicationContext.getBean("incidentSseExecutor", TaskExecutor.class))
			.isNotInstanceOf(TaskScheduler.class);
	}

	/**
	 * Application이 떠 있는 것만으로는 Report가 만들어지지 않는다. 기동할 때마다 만들면 배포가 잦은
	 * 날에는 같은 날짜를 몇 번이고 다시 만들려 들고, 아직 끝나지 않은 날짜가 대상이 될 수도 있다.
	 */
	@Test
	void createsNothingUntilTheTaskActuallyRuns() {
		assertThat(this.dailyIncidentReportRepository.count()).isZero();
	}

	/**
	 * 등록된 작업을 그대로 실행하면 완전히 끝난 전날의 Report가 실제 MySQL에 남는다. 여기서 쓰는
	 * 시각은 흉내 낸 것이 아니라 실행 시점의 진짜 시각이다.
	 */
	@Test
	void createsYesterdaysReportFromTheRealIncidentsWhenTheTaskRuns() {
		LocalDate expectedDate = DailyIncidentReportPeriod.lastCompletedDate(Instant.now());
		Instant openedAt = new DailyIncidentReportPeriod(expectedDate).start().plusSeconds(43200);
		openIncident(openedAt);

		runScheduledTask();

		DailyIncidentReport report = this.dailyIncidentReportRepository.findByReportDate(expectedDate).orElseThrow();
		assertThat(report.getPeriodStart()).isEqualTo(new DailyIncidentReportPeriod(expectedDate).start());
		assertThat(report.getPeriodEnd()).isEqualTo(new DailyIncidentReportPeriod(expectedDate).end());
		assertThat(report.getOpenedCount()).isEqualTo(1);
		assertThat(report.getErrorRateOpenedCount()).isEqualTo(1);
		assertThat(report.getUnresolvedAtPeriodEndCount()).isEqualTo(1);
		assertThat(report.getResolvedCount()).isZero();
		assertThat(report.getAverageResolutionSeconds()).isNull();
		assertThat(this.dailyIncidentReportRepository.count()).isEqualTo(1);
	}

	/** 같은 날짜로 다시 돌아도 행은 하나이며 처음 만든 Report가 그대로 남는다. */
	@Test
	void keepsASingleReportWhenTheTaskRunsAgainOnTheSameDate() {
		runScheduledTask();
		Long firstId = this.dailyIncidentReportRepository.findAll().get(0).getId();

		runScheduledTask();

		assertThat(this.dailyIncidentReportRepository.count()).isEqualTo(1);
		assertThat(this.dailyIncidentReportRepository.findAll().get(0).getId()).isEqualTo(firstId);
	}

	private void runScheduledTask() {
		cronTasks().get(0).getRunnable().run();
	}

	private List<CronTask> cronTasks() {
		return this.applicationContext.getBeansOfType(ScheduledTaskHolder.class)
			.values()
			.stream()
			.flatMap((holder) -> holder.getScheduledTasks().stream())
			.map(ScheduledTask::getTask)
			.filter(CronTask.class::isInstance)
			.map(CronTask.class::cast)
			.toList();
	}

	private void openIncident(Instant openedAt) {
		MetricSnapshot trigger = new MetricSnapshot("demo-shop", "GET", "/api/orders", openedAt.minusSeconds(60),
				openedAt, 100, 30, 30.0, 240L);
		this.incidentRepository
			.saveAndFlush(Incident.open(IncidentType.ERROR_RATE, IncidentSeverity.WARNING, trigger, openedAt));
	}

}

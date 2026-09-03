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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 MySQL에 남아 있는 Incident로 하루치 Report를 만들어, 무엇이 세어지고 무엇이 세어지지 않는지
 * 확정한다.
 * <p>
 * 저장소를 흉내 내지 않는다. 이 Phase에서 검증할 것은 집계 조건이 실제 SQL로 옮겨졌을 때에도 같은
 * 뜻인지이며, 특히 반개구간이 그렇다. Java에서 걸러 내는 방식으로는 경계를 포함하는지 아닌지의
 * 차이가 드러나지 않는다.
 * <p>
 * 같은 날짜를 다시 만들 수 없다는 것도 여기서 확인한다. 그 보장의 마지막 근거가 DB의 UNIQUE이므로
 * 실제 DB 없이는 검증할 수 없다.
 * <p>
 * Incident마다 endpoint를 다르게 둔다. 처리 중인 Incident는 계열과 종류마다 하나뿐이라는 UNIQUE가
 * 걸려 있어, 같은 계열로 여러 건을 열면 집계가 아니라 저장에서 먼저 막힌다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class DailyIncidentReportGenerationIntegrationTest {

	private static final LocalDate REPORT_DATE = LocalDate.of(2026, 9, 3);

	private static final LocalDate NEXT_DATE = LocalDate.of(2026, 9, 4);

	private static final Instant PERIOD_START = Instant.parse("2026-09-03T00:00:00Z");

	private static final Instant PERIOD_END = Instant.parse("2026-09-04T00:00:00Z");

	/** 구간의 마지막 순간이다. DATETIME(6)이 담을 수 있는 자리까지 채운다. */
	private static final Instant LAST_INSTANT_IN_PERIOD = Instant.parse("2026-09-03T23:59:59.999999Z");

	private static final Instant BEFORE_PERIOD = Instant.parse("2026-09-02T23:59:59.999999Z");

	private static final Instant MIDDAY = Instant.parse("2026-09-03T12:00:00Z");

	private static final Instant GENERATED_AT = Instant.parse("2026-09-04T00:05:00Z");

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "GET";

	private final AtomicInteger endpointSequence = new AtomicInteger();

	@Autowired
	private DailyIncidentReportService dailyIncidentReportService;

	@Autowired
	private DailyIncidentReportRepository dailyIncidentReportRepository;

	@Autowired
	private IncidentRepository incidentRepository;

	@AfterEach
	void removeEverything() {
		this.dailyIncidentReportRepository.deleteAll();
		this.incidentRepository.deleteAll();
	}

	/**
	 * Incident가 하나도 없는 날짜도 Report를 만든다. 아무 일도 없었다는 것 역시 그 하루의 결과이며,
	 * Report가 없는 날짜와 Scheduler가 돌지 못한 날짜는 구분되어야 한다.
	 */
	@Test
	void createsAReportForADayWithoutAnyIncident() {
		DailyIncidentReport report = generate().report();

		assertThat(report.getReportDate()).isEqualTo(REPORT_DATE);
		assertThat(report.getPeriodStart()).isEqualTo(PERIOD_START);
		assertThat(report.getPeriodEnd()).isEqualTo(PERIOD_END);
		assertThat(report.getOpenedCount()).isZero();
		assertThat(report.getErrorRateOpenedCount()).isZero();
		assertThat(report.getLatencyOpenedCount()).isZero();
		assertThat(report.getAcknowledgedCount()).isZero();
		assertThat(report.getResolvedCount()).isZero();
		assertThat(report.getUnresolvedAtPeriodEndCount()).isZero();
		assertThat(report.getAverageResolutionSeconds()).isNull();
		assertThat(report.getMaxResolutionSeconds()).isNull();
		assertThat(report.getGeneratedAt()).isEqualTo(GENERATED_AT);
	}

	/** 구간의 시작은 포함하고 끝은 포함하지 않는다. 자정 정각에 열린 Incident는 다음 날의 것이다. */
	@Test
	void countsOnlyTheIncidentsOpenedInsideTheHalfOpenPeriod() {
		open(IncidentType.ERROR_RATE, BEFORE_PERIOD);
		open(IncidentType.ERROR_RATE, PERIOD_START);
		open(IncidentType.ERROR_RATE, LAST_INSTANT_IN_PERIOD);
		open(IncidentType.ERROR_RATE, PERIOD_END);

		assertThat(generate().report().getOpenedCount()).isEqualTo(2);
	}

	/** 종류는 열릴 때 정해진 뒤 바뀌지 않으므로 나눠 세도 뜻이 흔들리지 않는다. */
	@Test
	void splitsTheOpenedCountByIncidentType() {
		open(IncidentType.ERROR_RATE, MIDDAY);
		open(IncidentType.ERROR_RATE, MIDDAY);
		open(IncidentType.LATENCY, MIDDAY);

		DailyIncidentReport report = generate().report();

		assertThat(report.getOpenedCount()).isEqualTo(3);
		assertThat(report.getErrorRateOpenedCount()).isEqualTo(2);
		assertThat(report.getLatencyOpenedCount()).isEqualTo(1);
	}

	/** 확인은 그 날짜에 열린 것 중에서가 아니라 그 날짜에 일어난 확인을 센다. */
	@Test
	void countsTheAcknowledgementsThatHappenedInsideThePeriod() {
		acknowledged(BEFORE_PERIOD, PERIOD_START);
		acknowledged(BEFORE_PERIOD, LAST_INSTANT_IN_PERIOD);
		acknowledged(BEFORE_PERIOD, PERIOD_END);
		acknowledged(BEFORE_PERIOD, BEFORE_PERIOD);

		DailyIncidentReport report = generate().report();

		assertThat(report.getAcknowledgedCount()).isEqualTo(2);
		assertThat(report.getOpenedCount()).isZero();
	}

	@Test
	void countsTheResolutionsThatHappenedInsideThePeriod() {
		resolved(BEFORE_PERIOD, PERIOD_START);
		resolved(BEFORE_PERIOD, LAST_INSTANT_IN_PERIOD);
		resolved(BEFORE_PERIOD, PERIOD_END);

		assertThat(generate().report().getResolvedCount()).isEqualTo(2);
	}

	/**
	 * 하나의 Incident가 여러 날에 걸쳐 있으면 각 날짜의 Report에 그 날 일어난 일로 한 번씩 등장한다.
	 * 열린 날짜에 모두 몰아 세면 아직 끝나지 않은 Incident의 해결을 셀 방법이 없어지고, 이미 만들어진
	 * Report를 계속 고쳐야만 그 값이 유지된다.
	 */
	@Test
	void reportsEachLifecycleEventOnTheDateItHappened() {
		Incident incident = open(IncidentType.LATENCY, Instant.parse("2026-09-02T10:00:00Z"));
		incident.acknowledge(Instant.parse("2026-09-03T10:00:00Z"));
		incident.resolve(Instant.parse("2026-09-04T10:00:00Z"));
		this.incidentRepository.saveAndFlush(incident);

		DailyIncidentReport openedDate = report(LocalDate.of(2026, 9, 2));
		DailyIncidentReport acknowledgedDate = report(REPORT_DATE);
		DailyIncidentReport resolvedDate = report(NEXT_DATE);

		assertThat(openedDate.getOpenedCount()).isEqualTo(1);
		assertThat(openedDate.getAcknowledgedCount()).isZero();
		assertThat(openedDate.getResolvedCount()).isZero();
		assertThat(acknowledgedDate.getOpenedCount()).isZero();
		assertThat(acknowledgedDate.getAcknowledgedCount()).isEqualTo(1);
		assertThat(acknowledgedDate.getResolvedCount()).isZero();
		assertThat(resolvedDate.getOpenedCount()).isZero();
		assertThat(resolvedDate.getAcknowledgedCount()).isZero();
		assertThat(resolvedDate.getResolvedCount()).isEqualTo(1);
	}

	/**
	 * 구간이 끝나는 순간에 남아 있던 부담을 센다. 그 날짜에 열린 것만 보면 며칠째 끝나지 않은
	 * Incident가 어느 Report에도 나타나지 않는다.
	 */
	@Test
	void countsWhatWasStillUnresolvedWhenThePeriodEnded() {
		resolved(BEFORE_PERIOD, Instant.parse("2026-09-05T00:00:00Z"));
		open(IncidentType.ERROR_RATE, MIDDAY);
		resolved(MIDDAY, LAST_INSTANT_IN_PERIOD);
		resolved(BEFORE_PERIOD, BEFORE_PERIOD);
		open(IncidentType.ERROR_RATE, PERIOD_END);

		assertThat(generate().report().getUnresolvedAtPeriodEndCount()).isEqualTo(2);
	}

	/**
	 * 구간의 끝 정각에 해결된 Incident는 아직 해결되지 않은 것으로 센다. 그 순간은 이미 다음 날에
	 * 속하므로 그 해결은 다음 날짜의 해결 건수에 세어진다. 한쪽만 포함하지 않으면 같은 해결이 두 번
	 * 세어지거나 어느 날짜에도 세어지지 않는다.
	 */
	@Test
	void treatsAResolutionExactlyAtThePeriodEndAsAnEventOfTheNextDate() {
		resolved(MIDDAY, PERIOD_END);

		DailyIncidentReport reportDate = generate().report();
		DailyIncidentReport nextDate = report(NEXT_DATE);

		assertThat(reportDate.getResolvedCount()).isZero();
		assertThat(reportDate.getUnresolvedAtPeriodEndCount()).isEqualTo(1);
		assertThat(nextDate.getResolvedCount()).isEqualTo(1);
	}

	/** 평균과 최댓값은 그 날짜에 해결된 Incident가 열려 있던 시간으로 구한다. */
	@Test
	void summarisesHowLongTheIncidentsResolvedThatDayHadBeenOpen() {
		resolved(MIDDAY, MIDDAY.plusSeconds(10));
		resolved(MIDDAY, MIDDAY.plusSeconds(20));
		resolved(MIDDAY, MIDDAY.plusSeconds(30));

		DailyIncidentReport report = generate().report();

		assertThat(report.getResolvedCount()).isEqualTo(3);
		assertThat(report.getAverageResolutionSeconds()).isEqualTo(20);
		assertThat(report.getMaxResolutionSeconds()).isEqualTo(30);
	}

	/** 나누어떨어지지 않는 평균은 초 단위로 반올림한다. */
	@Test
	void roundsTheAverageResolutionTimeToWholeSeconds() {
		resolved(MIDDAY, MIDDAY.plusSeconds(10));
		resolved(MIDDAY, MIDDAY.plusSeconds(11));

		assertThat(generate().report().getAverageResolutionSeconds()).isEqualTo(11);
	}

	/**
	 * 해결된 Incident가 없으면 통계도 없다. 0으로 두면 아무것도 해결하지 못한 하루가 모든 것을 즉시
	 * 해결한 하루처럼 보인다.
	 */
	@Test
	void leavesTheResolutionStatisticsEmptyWhenNothingWasResolved() {
		open(IncidentType.ERROR_RATE, MIDDAY);

		DailyIncidentReport report = generate().report();

		assertThat(report.getResolvedCount()).isZero();
		assertThat(report.getAverageResolutionSeconds()).isNull();
		assertThat(report.getMaxResolutionSeconds()).isNull();
	}

	/** 같은 날짜를 몇 번을 불러도 Report는 하나이며 처음 만든 그것이 그대로 남는다. */
	@Test
	void keepsASingleReportWhenTheSameDateIsGeneratedAgain() {
		open(IncidentType.ERROR_RATE, MIDDAY);
		DailyIncidentReportGenerationResult first = generate();

		DailyIncidentReportGenerationResult second = generate();
		DailyIncidentReportGenerationResult third = generate();

		assertThat(first.created()).isTrue();
		assertThat(second.created()).isFalse();
		assertThat(third.created()).isFalse();
		assertThat(second.report().getId()).isEqualTo(first.report().getId());
		assertThat(third.report().getId()).isEqualTo(first.report().getId());
		assertThat(this.dailyIncidentReportRepository.count()).isEqualTo(1);
	}

	/** 처음 만든 뒤 Incident가 달라져도 이미 만들어진 Report의 숫자는 움직이지 않는다. */
	@Test
	void keepsTheFirstAggregateEvenWhenTheIncidentsChangeAfterwards() {
		open(IncidentType.ERROR_RATE, MIDDAY);
		generate();

		open(IncidentType.LATENCY, MIDDAY);

		assertThat(generate().report().getOpenedCount()).isEqualTo(1);
	}

	/**
	 * 두 실행이 같은 날짜를 동시에 만들려 해도 행은 하나이다. 앞의 존재 확인만으로는 둘 다 "없다"를
	 * 볼 수 있으므로, 마지막으로 막는 것은 {@code report_date}의 UNIQUE이다. 진 쪽은 실패로 끝나지
	 * 않고 이긴 쪽이 만든 Report를 그대로 돌려받는다.
	 * <p>
	 * 시각으로 순서를 맞추지 않는다. 두 실행은 같은 지점에서 함께 출발하고 그 뒤의 순서는 DB가 정한다.
	 */
	@Test
	void createsOneReportWhenTwoExecutionsRaceOnTheSameDate() throws Exception {
		open(IncidentType.ERROR_RATE, MIDDAY);
		CyclicBarrier start = new CyclicBarrier(2);
		Callable<DailyIncidentReportGenerationResult> generate = () -> {
			start.await(10, TimeUnit.SECONDS);
			return this.dailyIncidentReportService.generate(REPORT_DATE, GENERATED_AT);
		};

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<DailyIncidentReportGenerationResult> left = executor.submit(generate);
			Future<DailyIncidentReportGenerationResult> right = executor.submit(generate);
			DailyIncidentReportGenerationResult leftResult = left.get(30, TimeUnit.SECONDS);
			DailyIncidentReportGenerationResult rightResult = right.get(30, TimeUnit.SECONDS);

			assertThat(this.dailyIncidentReportRepository.count()).isEqualTo(1);
			assertThat(List.of(leftResult.created(), rightResult.created())).containsExactlyInAnyOrder(true, false);
			assertThat(leftResult.report().getId()).isEqualTo(rightResult.report().getId());
		}
		finally {
			executor.shutdownNow();
		}
	}

	/** 날짜가 다르면 서로 다른 Report이다. 하나뿐인 Report에 계속 덮어쓰지 않는다. */
	@Test
	void keepsOneReportPerDate() {
		generate();
		report(NEXT_DATE);

		assertThat(this.dailyIncidentReportRepository.count()).isEqualTo(2);
		assertThat(this.dailyIncidentReportRepository.findByReportDate(REPORT_DATE)).isPresent();
		assertThat(this.dailyIncidentReportRepository.findByReportDate(NEXT_DATE)).isPresent();
	}

	private DailyIncidentReportGenerationResult generate() {
		return this.dailyIncidentReportService.generate(REPORT_DATE, GENERATED_AT);
	}

	/**
	 * 다른 날짜의 Report를 만든다. 생성 시각은 어느 날짜를 만들든 집계에 쓰이지 않으므로 하나로 둔다.
	 * 그 값이 집계에 영향을 준다면 그것 자체가 잘못이다.
	 */
	private DailyIncidentReport report(LocalDate reportDate) {
		return this.dailyIncidentReportService.generate(reportDate, GENERATED_AT).report();
	}

	private Incident open(IncidentType type, Instant openedAt) {
		return this.incidentRepository.saveAndFlush(newIncident(type, openedAt));
	}

	private void acknowledged(Instant openedAt, Instant acknowledgedAt) {
		Incident incident = newIncident(IncidentType.ERROR_RATE, openedAt);
		incident.acknowledge(acknowledgedAt);
		this.incidentRepository.saveAndFlush(incident);
	}

	private void resolved(Instant openedAt, Instant resolvedAt) {
		Incident incident = newIncident(IncidentType.ERROR_RATE, openedAt);
		incident.resolve(resolvedAt);
		this.incidentRepository.saveAndFlush(incident);
	}

	private Incident newIncident(IncidentType type, Instant openedAt) {
		String endpoint = "/api/orders/" + this.endpointSequence.incrementAndGet();
		MetricSnapshot trigger = new MetricSnapshot(SERVICE, METHOD, endpoint, openedAt.minusSeconds(60), openedAt, 100,
				30, 30.0, 240L);
		return Incident.open(type, IncidentSeverity.WARNING, trigger, openedAt);
	}

}

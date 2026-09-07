package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.metric.MetricSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 관측값이 저장된 사실만 따라가는지 실제 MySQL 위에서 확인한다.
 * <p>
 * {@link IncidentMetricsTest}는 이미 일어난 전이가 어떤 숫자가 되는지를 본다. 여기서 볼 것은 그 전이가
 * 언제 세어지는가이다. 판정 Transaction은 교착이나 제약 위반으로 되돌아갈 수 있고, 되돌아간 전이까지
 * 세면 일어나지 않은 장애가 Counter에 남는다. 그 성질은 실제 Transaction 경계 위에서만 확인된다.
 * <p>
 * 판정을 다시 돌리지 않는다. 언제 Incident가 열리는지는 {@link IncidentDetectorIntegrationTest}가 이미
 * 보고 있고, 그것을 여기서 또 세우면 이 테스트가 무엇을 지키는지 보이지 않는다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentMetricsIntegrationTest {

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "GET";

	private static final String ENDPOINT = "/api/orders/{orderId}";

	private static final Instant WINDOW_START = Instant.parse("2026-08-31T12:34:00Z");

	private static final Instant WINDOW_END = Instant.parse("2026-08-31T12:35:00Z");

	private static final MetricSnapshot TRIGGER = new MetricSnapshot(SERVICE, METHOD, ENDPOINT, WINDOW_START,
			WINDOW_END, 120, 30, 25.0, 240L);

	/**
	 * 처리 중인 건수는 짧은 시간 동안 직전에 읽은 값을 그대로 쓴다. 한 번의 scrape가 하나의 장면을
	 * 뜻하게 하려는 것이며, 그래서 방금 저장한 Incident가 Gauge에 나타나기까지 그만큼 걸린다.
	 */
	private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(10);

	private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	private MeterRegistry meterRegistry;

	@AfterEach
	void clearIncidents() {
		this.incidentRepository.deleteAll();
	}

	/** commit된 전이는 세어진다. 이것이 Counter가 뜻하기로 한 사실이다. */
	@Test
	void countsATransitionThatWasCommitted() {
		double before = openedCount(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL);

		this.transactionTemplate.executeWithoutResult((status) -> this.eventPublisher
			.publishEvent(new IncidentLifecycleEvent(IncidentLifecycleTransition.OPENED, IncidentType.ERROR_RATE,
					IncidentSeverity.CRITICAL)));

		assertThat(openedCount(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL)).isEqualTo(before + 1.0);
	}

	/**
	 * 되돌아간 Transaction의 전이는 세지 않는다. 그 Incident는 저장되지 않았고, 세어 두면 대시보드에
	 * 일어나지 않은 장애가 남는다.
	 */
	@Test
	void doesNotCountATransitionThatWasRolledBack() {
		double before = openedCount(IncidentType.LATENCY, IncidentSeverity.WARNING);

		this.transactionTemplate.executeWithoutResult((status) -> {
			this.eventPublisher.publishEvent(new IncidentLifecycleEvent(IncidentLifecycleTransition.OPENED,
					IncidentType.LATENCY, IncidentSeverity.WARNING));
			status.setRollbackOnly();
		});

		assertThat(openedCount(IncidentType.LATENCY, IncidentSeverity.WARNING)).isEqualTo(before);
	}

	/**
	 * 처리 중인 건수의 근거는 DB이다. 이 Instance가 열지 않은 Incident도, 사람이 확인 처리해 상태가
	 * 바뀐 Incident도 같은 조회 하나로 세어진다.
	 */
	@Test
	void countsActiveIncidentsFromTheDatabaseRatherThanFromTransitions() {
		this.incidentRepository
			.save(Incident.open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL, TRIGGER, WINDOW_END));

		awaitActiveCount(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL, 1.0);
	}

	/** 끝난 Incident는 처리 중이 아니다. 화면의 목록과 이 Gauge가 같은 정의를 써야 한다. */
	@Test
	void stopsCountingAnIncidentOnceItIsResolved() {
		Incident incident = this.incidentRepository
			.save(Incident.open(IncidentType.LATENCY, IncidentSeverity.WARNING, TRIGGER, WINDOW_END));
		awaitActiveCount(IncidentType.LATENCY, IncidentSeverity.WARNING, 1.0);

		this.transactionTemplate.executeWithoutResult((status) -> this.incidentRepository
			.findByIdForUpdate(incident.getId())
			.orElseThrow()
			.resolve(WINDOW_END.plusSeconds(60)));

		awaitActiveCount(IncidentType.LATENCY, IncidentSeverity.WARNING, 0.0);
	}

	private void awaitActiveCount(IncidentType type, IncidentSeverity severity, double expected) {
		await().atMost(SNAPSHOT_TIMEOUT)
			.pollInterval(POLL_INTERVAL)
			.untilAsserted(() -> assertThat(activeCount(type, severity)).isEqualTo(expected));
	}

	private double activeCount(IncidentType type, IncidentSeverity severity) {
		return this.meterRegistry.get(IncidentMetrics.ACTIVE_GAUGE)
			.tag(IncidentMetrics.TYPE_TAG, type.name())
			.tag(IncidentMetrics.SEVERITY_TAG, severity.name())
			.gauge()
			.value();
	}

	private double openedCount(IncidentType type, IncidentSeverity severity) {
		return this.meterRegistry.get(IncidentMetrics.OPENED_COUNTER)
			.tag(IncidentMetrics.TYPE_TAG, type.name())
			.tag(IncidentMetrics.SEVERITY_TAG, severity.name())
			.counter()
			.count();
	}

}

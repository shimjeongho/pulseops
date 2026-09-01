package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.metric.MetricSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Incident가 실제 MySQL에 어떤 값으로 저장되고 어떤 값으로 돌아오는지 검증한다.
 * <p>
 * Context가 뜨는 것 자체가 첫 번째 검증이다. {@code ddl-auto=validate}가 Flyway로 만든 incident
 * table과 Entity mapping을 대조하므로, 열 이름이나 길이, 자리수, null 허용이 어긋나면 이 Class의
 * 모든 테스트가 기동 단계에서 실패한다.
 * <p>
 * 저장된 값은 Java에서 읽은 것만 보지 않고 SQL로도 직접 확인한다. Enum이 문자열로 들어갔는지, 시각이
 * 시간대 변환 없이 UTC로 들어갔는지는 Java 쪽에서 왕복시켜 보면 두 번의 변환이 서로를 가려 통과해
 * 버린다.
 * <p>
 * Transaction 없이 Repository를 호출하므로 호출마다 EntityManager가 새로 만들어진다. 저장 뒤의 조회는
 * 1차 Cache가 아니라 실제 SELECT이다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentPersistenceIntegrationTest {

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "GET";

	private static final String ENDPOINT = "/api/orders/{orderId}";

	/** DATETIME(6)까지 남는지 보려고 마이크로초 자리를 채운다. */
	private static final Instant WINDOW_START = Instant.parse("2026-08-31T12:34:00.123456Z");

	private static final Instant WINDOW_END = Instant.parse("2026-08-31T12:35:00.654321Z");

	private static final Instant ACKNOWLEDGED_AT = Instant.parse("2026-08-31T12:36:30.111222Z");

	private static final Instant RESOLVED_AT = Instant.parse("2026-08-31T12:40:00.999888Z");

	private static final MetricSnapshot TRIGGER = new MetricSnapshot(SERVICE, METHOD, ENDPOINT, WINDOW_START,
			WINDOW_END, 120, 30, 25.0, 240L);

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void removeIncidents() {
		this.incidentRepository.deleteAll();
	}

	@Test
	void storesAndReloadsEveryField() {
		Long id = this.incidentRepository.saveAndFlush(openWarning()).getId();

		Incident reloaded = reload(id);

		assertThat(reloaded.getService()).isEqualTo(SERVICE);
		assertThat(reloaded.getMethod()).isEqualTo(METHOD);
		assertThat(reloaded.getEndpoint()).isEqualTo(ENDPOINT);
		assertThat(reloaded.getType()).isEqualTo(IncidentType.ERROR_RATE);
		assertThat(reloaded.getSeverity()).isEqualTo(IncidentSeverity.WARNING);
		assertThat(reloaded.getStatus()).isEqualTo(IncidentStatus.OPEN);
		assertThat(reloaded.getOpenedAt()).isEqualTo(WINDOW_END);
		assertThat(reloaded.getUpdatedAt()).isEqualTo(WINDOW_END);
		assertThat(reloaded.getTriggerWindowStart()).isEqualTo(WINDOW_START);
		assertThat(reloaded.getTriggerWindowEnd()).isEqualTo(WINDOW_END);
		assertThat(reloaded.getTriggerRequestCount()).isEqualTo(120);
		assertThat(reloaded.getTriggerErrorCount()).isEqualTo(30);
		assertThat(reloaded.getTriggerErrorRate()).isEqualByComparingTo(new BigDecimal("25.00"));
		assertThat(reloaded.getTriggerP95LatencyMs()).isEqualTo(240L);
	}

	/** 아직 아무도 확인하지 않았고 끝나지도 않은 Incident의 두 시각은 비어 있어야 한다. */
	@Test
	void leavesAcknowledgedAndResolvedEmptyWhileOpen() {
		Long id = this.incidentRepository.saveAndFlush(openWarning()).getId();

		Incident reloaded = reload(id);

		assertThat(reloaded.getAcknowledgedAt()).isNull();
		assertThat(reloaded.getResolvedAt()).isNull();
		assertThat(this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM incident WHERE id = ? AND acknowledged_at IS NULL AND resolved_at IS NULL",
				Integer.class, id)).isEqualTo(1);
	}

	@Test
	void persistsTheTimesOfEachTransition() {
		Incident incident = this.incidentRepository.saveAndFlush(openWarning());
		incident.acknowledge(ACKNOWLEDGED_AT);
		this.incidentRepository.saveAndFlush(incident);
		incident.resolve(RESOLVED_AT);
		this.incidentRepository.saveAndFlush(incident);

		Incident reloaded = reload(incident.getId());

		assertThat(reloaded.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
		assertThat(reloaded.getAcknowledgedAt()).isEqualTo(ACKNOWLEDGED_AT);
		assertThat(reloaded.getResolvedAt()).isEqualTo(RESOLVED_AT);
		assertThat(reloaded.getUpdatedAt()).isEqualTo(RESOLVED_AT);
	}

	/**
	 * 저장된 시각이 JVM 시간대로 옮겨지지 않았는지 SQL로 직접 본다. UTC 계약이 깨지면 이 문자열이
	 * 시간대만큼 어긋난 값으로 나온다.
	 */
	@Test
	void storesTimesAsUtcWithMicrosecondPrecision() {
		Long id = this.incidentRepository.saveAndFlush(openWarning()).getId();

		assertThat(storedValueOf(id, "opened_at")).isEqualTo("2026-08-31 12:35:00.654321");
		assertThat(storedValueOf(id, "trigger_window_start")).isEqualTo("2026-08-31 12:34:00.123456");
		assertThat(reload(id).getOpenedAt()).isEqualTo(WINDOW_END);
	}

	/** ordinal로 저장되면 값의 순서를 바꾸는 순간 과거 기록의 뜻이 통째로 달라진다. */
	@Test
	void storesEnumsAsTheirNames() {
		Long id = this.incidentRepository.saveAndFlush(openWarning()).getId();

		assertThat(storedValueOf(id, "type")).isEqualTo("ERROR_RATE");
		assertThat(storedValueOf(id, "severity")).isEqualTo("WARNING");
		assertThat(storedValueOf(id, "status")).isEqualTo("OPEN");
	}

	@Test
	void storesTheErrorRateWithTheColumnScale() {
		Long id = this.incidentRepository.saveAndFlush(openWarning()).getId();

		assertThat(storedValueOf(id, "trigger_error_rate")).isEqualTo("25.00");
		assertThat(reload(id).getTriggerErrorRate().scale()).isEqualTo(2);
	}

	@Test
	void storesAMissingP95AsNull() {
		MetricSnapshot withoutSamples = new MetricSnapshot(SERVICE, METHOD, ENDPOINT, WINDOW_START, WINDOW_END, 120, 30,
				25.0, null);

		Long id = this.incidentRepository
			.saveAndFlush(Incident.open(IncidentType.LATENCY, IncidentSeverity.WARNING, withoutSamples, WINDOW_END))
			.getId();

		assertThat(reload(id).getTriggerP95LatencyMs()).isNull();
		assertThat(storedValueOf(id, "trigger_p95_latency_ms")).isNull();
	}

	/**
	 * active_key는 Entity에 없다. JPA가 그 열을 쓰려 들었다면 MySQL이 생성 열에 대한 쓰기를 거부해
	 * 저장 자체가 실패하므로, 저장이 성공하고 값이 채워져 있다는 것이 곧 DB가 파생시켰다는 뜻이다.
	 * 해결하면 값이 사라져 다음 Incident가 그 자리를 쓸 수 있게 되는 것까지 확인한다.
	 */
	@Test
	void letsTheDatabaseDeriveActiveKey() {
		Incident incident = this.incidentRepository.saveAndFlush(openWarning());

		assertThat(storedValueOf(incident.getId(), "active_key"))
			.isEqualTo(SERVICE + "|" + METHOD + "|" + ENDPOINT + "|ERROR_RATE");

		incident.resolve(RESOLVED_AT);
		this.incidentRepository.saveAndFlush(incident);

		assertThat(storedValueOf(incident.getId(), "active_key")).isNull();
	}

	private Incident reload(Long id) {
		return this.incidentRepository.findById(id).orElseThrow();
	}

	/** 값을 Java Type으로 되돌리지 않고 저장된 그대로 읽는다. */
	private String storedValueOf(Long id, String column) {
		return this.jdbcTemplate.queryForObject("SELECT " + column + " FROM incident WHERE id = ?", String.class, id);
	}

	private static Incident openWarning() {
		return Incident.open(IncidentType.ERROR_RATE, IncidentSeverity.WARNING, TRIGGER, WINDOW_END);
	}

}

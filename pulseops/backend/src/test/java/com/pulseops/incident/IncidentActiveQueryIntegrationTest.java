package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.metric.MetricSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 처리 중인 Incident를 전부 읽는 조회가 실제 MySQL에서 무엇을 돌려주는지 고정한다.
 * <p>
 * 이 조회의 결과가 곧 화면에 보이는 목록이다. 끝난 Incident가 섞여 나오면 아무도 보지 않아도 되는
 * 기록이 계속 쌓여 목록을 채우고, 확인된 Incident가 빠지면 사람이 붙어 있는 장애가 화면에서 사라진다.
 * 그래서 무엇이 걸려 나오고 무엇이 빠지는지가 이 조회의 계약이다.
 * <p>
 * 그 계약은 {@link IncidentRepository#findActiveForUpdate}와 같아야 한다. 두 조회가 서로 다른 상태를
 * 처리 중이라고 보면, 화면에 없는 Incident 때문에 새 Incident가 열리지 않는 상태에 이른다.
 * <p>
 * Transaction 없이 부른다. 조회 API가 쓰는 자리이므로 잠그지 않아야 하고, 잠그는 조회였다면 여기서
 * 바로 실패한다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentActiveQueryIntegrationTest {

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "POST";

	private static final String ENDPOINT = "/api/orders";

	private static final Instant WINDOW_START = Instant.parse("2026-09-01T05:59:00Z");

	private static final Instant OPENED_AT = Instant.parse("2026-09-01T06:00:00Z");

	private static final Instant ACKNOWLEDGED_AT = Instant.parse("2026-09-01T06:01:00Z");

	private static final Instant RESOLVED_AT = Instant.parse("2026-09-01T06:05:00Z");

	@Autowired
	private IncidentRepository incidentRepository;

	@AfterEach
	void removeIncidents() {
		this.incidentRepository.deleteAll();
	}

	@Test
	void findsAnOpenIncident() {
		this.incidentRepository.saveAndFlush(open(ENDPOINT, IncidentType.ERROR_RATE));

		assertThat(this.incidentRepository.findActive()).extracting(Incident::getStatus)
			.containsExactly(IncidentStatus.OPEN);
	}

	/** 사람이 확인한 장애도 끝난 것은 아니므로 목록에서 사라지면 안 된다. */
	@Test
	void findsAnAcknowledgedIncident() {
		Incident incident = open(ENDPOINT, IncidentType.ERROR_RATE);
		incident.acknowledge(ACKNOWLEDGED_AT);
		this.incidentRepository.saveAndFlush(incident);

		assertThat(this.incidentRepository.findActive()).extracting(Incident::getStatus)
			.containsExactly(IncidentStatus.ACKNOWLEDGED);
	}

	/** 끝난 기록은 이력이며 한 계열에 얼마든지 쌓인다. 그것이 목록에 남으면 화면이 이력으로 덮인다. */
	@Test
	void ignoresAResolvedIncident() {
		Incident incident = open(ENDPOINT, IncidentType.ERROR_RATE);
		incident.resolve(RESOLVED_AT);
		this.incidentRepository.saveAndFlush(incident);

		assertThat(this.incidentRepository.findActive()).isEmpty();
	}

	@Test
	void findsNothingWhenNoIncidentIsActive() {
		assertThat(this.incidentRepository.findActive()).isEmpty();
	}

	/**
	 * 오류율과 응답 시간은 같은 계열에서도 서로 다른 Incident로 열린다. 둘 중 하나만 걸려 나오면
	 * 화면이 실제로 일어나고 있는 이상의 절반만 보여 준다.
	 */
	@Test
	void findsBothTypesOfTheSameSeries() {
		this.incidentRepository.saveAndFlush(open(ENDPOINT, IncidentType.ERROR_RATE));
		this.incidentRepository.saveAndFlush(open(ENDPOINT, IncidentType.LATENCY));

		assertThat(this.incidentRepository.findActive()).extracting(Incident::getType)
			.containsExactlyInAnyOrder(IncidentType.ERROR_RATE, IncidentType.LATENCY);
	}

	/** 처리 중인 것만 남기고 끝난 이력은 같은 계열에 쌓여 있어도 걸러져야 한다. */
	@Test
	void findsOnlyTheActiveOnesWhenHistoryHasPiledUp() {
		Incident resolved = open(ENDPOINT, IncidentType.ERROR_RATE);
		resolved.resolve(RESOLVED_AT);
		this.incidentRepository.saveAndFlush(resolved);
		this.incidentRepository.saveAndFlush(open(ENDPOINT, IncidentType.ERROR_RATE));
		this.incidentRepository.saveAndFlush(open("/api/payments", IncidentType.LATENCY));

		assertThat(this.incidentRepository.findActive()).hasSize(2)
			.extracting(Incident::getStatus)
			.containsOnly(IncidentStatus.OPEN);
	}

	private static Incident open(String endpoint, IncidentType type) {
		MetricSnapshot trigger = new MetricSnapshot(SERVICE, METHOD, endpoint, WINDOW_START, OPENED_AT, 120, 30, 25.0,
				240L);
		return Incident.open(type, IncidentSeverity.WARNING, trigger, OPENED_AT);
	}

}

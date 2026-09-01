package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.metric.MetricSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 한 계열의 한 종류에 처리 중인 Incident가 둘 이상 생기지 않는다는 계약을 JPA 경로로 검증한다.
 * <p>
 * Phase 4-1이 같은 제약을 JDBC로 확인했지만 그것은 DDL이 맞게 만들어졌는지를 본 것이다. 여기서 보는
 * 것은 다르다. Entity가 {@code active_key}를 갖지 않은 채로 저장과 상태 전이를 하더라도 DB가 그 열을
 * 제때 채우고 비워, 실제로 Repository를 통해 중복 Incident를 만들 수 없는지를 본다. 이 계약이 깨지면
 * 같은 장애에 대한 기록이 둘로 갈라져 어느 쪽이 진짜인지 말할 수 없게 된다.
 * <p>
 * 각 검증은 자기가 만든 Incident만 두고 끝나면 모두 지운다. 그래서 실행 순서에 의존하지 않는다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentActiveUniquenessIntegrationTest {

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "GET";

	private static final String ENDPOINT = "/api/orders/{orderId}";

	private static final Instant WINDOW_START = Instant.parse("2026-08-31T12:34:00Z");

	private static final Instant WINDOW_END = Instant.parse("2026-08-31T12:35:00Z");

	private static final Instant ACKNOWLEDGED_AT = Instant.parse("2026-08-31T12:36:30Z");

	private static final Instant RESOLVED_AT = Instant.parse("2026-08-31T12:40:00Z");

	@Autowired
	private IncidentRepository incidentRepository;

	@AfterEach
	void removeIncidents() {
		this.incidentRepository.deleteAll();
	}

	@Test
	void rejectsASecondOpenIncidentForTheSameIdentity() {
		save(open(METHOD, ENDPOINT, IncidentType.ERROR_RATE));
		Incident duplicate = open(METHOD, ENDPOINT, IncidentType.ERROR_RATE);

		assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> save(duplicate));

		assertThat(this.incidentRepository.count()).isEqualTo(1);
	}

	/** 사람이 확인 중인 Incident도 그 자리를 차지한다. 확인했다고 해서 같은 장애가 다시 열리면 안 된다. */
	@Test
	void rejectsANewIncidentWhileTheIdentityIsAcknowledged() {
		Incident acknowledged = save(open(METHOD, ENDPOINT, IncidentType.ERROR_RATE));
		acknowledged.acknowledge(ACKNOWLEDGED_AT);
		save(acknowledged);

		Incident duplicate = open(METHOD, ENDPOINT, IncidentType.ERROR_RATE);

		assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> save(duplicate));

		assertThat(this.incidentRepository.count()).isEqualTo(1);
	}

	/** 끝난 Incident는 이력이므로 같은 계열에 얼마든지 쌓인다. */
	@Test
	void allowsManyResolvedIncidentsForTheSameIdentity() {
		resolveNewIncident();
		resolveNewIncident();
		resolveNewIncident();

		assertThat(this.incidentRepository.count()).isEqualTo(3);
	}

	/** 같은 문제가 다시 나면 끝난 기록을 되살리지 않고 새 Incident를 연다. */
	@Test
	void allowsANewIncidentAfterTheActiveOneIsResolved() {
		resolveNewIncident();

		Incident reopened = save(open(METHOD, ENDPOINT, IncidentType.ERROR_RATE));

		assertThat(reopened.getStatus()).isEqualTo(IncidentStatus.OPEN);
		assertThat(this.incidentRepository.count()).isEqualTo(2);
	}

	/** 오류율과 응답 시간은 서로 다른 이상이므로 같은 계열에서 동시에 열릴 수 있다. */
	@Test
	void allowsOneActiveIncidentPerType() {
		save(open(METHOD, ENDPOINT, IncidentType.ERROR_RATE));
		save(open(METHOD, ENDPOINT, IncidentType.LATENCY));

		assertThat(this.incidentRepository.count()).isEqualTo(2);
	}

	/** 계열은 method와 endpoint까지 포함하므로 어느 하나만 달라도 서로 다른 자리이다. */
	@Test
	void allowsActiveIncidentsForDifferentMethodsAndEndpoints() {
		save(open(METHOD, ENDPOINT, IncidentType.ERROR_RATE));
		save(open("POST", ENDPOINT, IncidentType.ERROR_RATE));
		save(open(METHOD, "/api/payments", IncidentType.ERROR_RATE));

		assertThat(this.incidentRepository.count()).isEqualTo(3);
	}

	private void resolveNewIncident() {
		Incident incident = save(open(METHOD, ENDPOINT, IncidentType.ERROR_RATE));
		incident.resolve(RESOLVED_AT);
		save(incident);
	}

	private Incident save(Incident incident) {
		return this.incidentRepository.saveAndFlush(incident);
	}

	private static Incident open(String method, String endpoint, IncidentType type) {
		MetricSnapshot trigger = new MetricSnapshot(SERVICE, method, endpoint, WINDOW_START, WINDOW_END, 120, 30, 25.0,
				240L);
		return Incident.open(type, IncidentSeverity.WARNING, trigger, WINDOW_END);
	}

}

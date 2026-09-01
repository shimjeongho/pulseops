package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.metric.MetricSeries;
import com.pulseops.metric.MetricSnapshot;
import jakarta.persistence.TransactionRequiredException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 처리 중인 Incident를 잠그고 찾는 조회가 실제 MySQL에서 성립하는지 검증한다.
 * <p>
 * 이 조회의 결과가 곧 Detection의 분기이다. 없으면 새로 열고 있으면 심각도를 올리거나 끝낸다.
 * 그래서 무엇이 걸려 나오고 무엇이 빠지는지가 조회의 성능이 아니라 계약이다. 끝난 기록이 걸려
 * 나오면 같은 문제가 다시 났을 때 새 Incident가 열리지 않고, 다른 종류가 걸려 나오면 오류율의
 * 이상이 응답 시간의 기록을 건드린다.
 * <p>
 * 잠금이 실제로 걸리는지는 값만 보고 알 수 없어 Transaction 안과 밖의 차이로 확인한다. 잠금이 없는
 * 조회였다면 Transaction 밖에서도 그냥 성공했을 자리다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentActiveLookupIntegrationTest {

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "GET";

	private static final String ENDPOINT = "/api/orders/{orderId}";

	private static final MetricSeries SERIES = new MetricSeries(SERVICE, METHOD, ENDPOINT);

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
	@Transactional
	void findsAnOpenIncident() {
		this.incidentRepository.saveAndFlush(open(IncidentType.ERROR_RATE));

		assertThat(findActiveForUpdate(IncidentType.ERROR_RATE)).get().satisfies((incident) -> {
			assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
			assertThat(incident.getType()).isEqualTo(IncidentType.ERROR_RATE);
		});
	}

	/** 사람이 확인했다고 해서 장애가 끝난 것은 아니므로 여전히 처리 중이다. */
	@Test
	@Transactional
	void findsAnAcknowledgedIncident() {
		Incident incident = this.incidentRepository.saveAndFlush(open(IncidentType.ERROR_RATE));
		incident.acknowledge(ACKNOWLEDGED_AT);

		assertThat(findActiveForUpdate(IncidentType.ERROR_RATE)).get()
			.extracting(Incident::getStatus)
			.isEqualTo(IncidentStatus.ACKNOWLEDGED);
	}

	/** 끝난 기록이 걸려 나오면 같은 문제가 다시 났을 때 그 기록을 되살리게 된다. */
	@Test
	@Transactional
	void ignoresAResolvedIncident() {
		Incident incident = this.incidentRepository.saveAndFlush(open(IncidentType.ERROR_RATE));
		incident.resolve(RESOLVED_AT);

		assertThat(findActiveForUpdate(IncidentType.ERROR_RATE)).isEmpty();
	}

	/** 오류율과 응답 시간은 서로 다른 이상이라 한쪽의 기록이 다른 쪽의 판정에 걸려 나오면 안 된다. */
	@Test
	@Transactional
	void ignoresAnIncidentOfAnotherType() {
		this.incidentRepository.saveAndFlush(open(IncidentType.ERROR_RATE));

		assertThat(findActiveForUpdate(IncidentType.LATENCY)).isEmpty();
	}

	/** 계열은 method와 endpoint까지 포함하므로 어느 하나만 달라도 서로 다른 자리이다. */
	@Test
	@Transactional
	void ignoresAnIncidentOfAnotherSeries() {
		this.incidentRepository.saveAndFlush(open(IncidentType.ERROR_RATE));

		Optional<Incident> otherMethod = this.incidentRepository.findActiveForUpdate(SERVICE, "POST", ENDPOINT,
				IncidentType.ERROR_RATE);

		assertThat(otherMethod).isEmpty();
	}

	/** 잠금은 Transaction의 범위만큼만 유지되므로 Transaction 밖의 잠금 조회는 뜻이 없다. */
	@Test
	void refusesToLockOutsideATransaction() {
		this.incidentRepository.saveAndFlush(open(IncidentType.ERROR_RATE));

		assertThatThrownBy(() -> findActiveForUpdate(IncidentType.ERROR_RATE))
			.isInstanceOf(InvalidDataAccessApiUsageException.class)
			.hasRootCauseInstanceOf(TransactionRequiredException.class);
	}

	private Optional<Incident> findActiveForUpdate(IncidentType type) {
		return this.incidentRepository.findActiveForUpdate(SERIES.service(), SERIES.method(), SERIES.endpoint(), type);
	}

	private static Incident open(IncidentType type) {
		MetricSnapshot trigger = new MetricSnapshot(SERVICE, METHOD, ENDPOINT, WINDOW_START, WINDOW_END, 120, 30, 25.0,
				240L);
		return Incident.open(type, IncidentSeverity.WARNING, trigger, WINDOW_END);
	}

}

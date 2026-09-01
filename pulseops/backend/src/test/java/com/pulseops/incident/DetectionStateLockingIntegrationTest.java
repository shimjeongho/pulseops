package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.metric.MetricSeries;
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
 * Detection State를 잠그고 읽는 조회가 실제 MySQL에서 성립하는지 검증한다.
 * <p>
 * 이 조회를 쓰는 쪽은 읽은 값을 바탕으로 연속 횟수를 고쳐 쓰므로, 읽기와 쓰기 사이에 다른 처리가
 * 끼어들면 한쪽의 창이 통째로 사라진다. 잠금이 실제로 걸리는지는 Java 쪽에서 값만 보고는 알 수 없어
 * Transaction 안과 밖의 차이로 확인한다. Transaction이 없을 때 조회가 거절된다는 것은 잠금이 요청
 * 단계에서부터 실재한다는 뜻이며, 잠금 없는 조회였다면 그냥 성공했을 자리다.
 * <p>
 * 두 Thread가 실제로 서로를 기다리는지는 여기서 보지 않는다. 그 검증은 잠금 구간을 정하는 쪽,
 * 즉 이 조회를 Transaction 안에서 호출하는 코드가 생겼을 때 그 코드의 계약으로 확인해야 한다.
 * <p>
 * 이 잠금은 이미 있는 행에만 걸린다. 행이 없을 때 두 처리가 나란히 insert하는 것은 잠금이 아니라
 * identity UNIQUE가 막으며, 그 계약은 {@link DetectionStatePersistenceIntegrationTest}가 확인한다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class DetectionStateLockingIntegrationTest {

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "GET";

	private static final String ENDPOINT = "/api/orders/{orderId}";

	private static final MetricSeries SERIES = new MetricSeries(SERVICE, METHOD, ENDPOINT);

	private static final Instant FIRST_WINDOW_END = Instant.parse("2026-08-31T12:00:30Z");

	@Autowired
	private DetectionStateRepository detectionStateRepository;

	@AfterEach
	void removeDetectionStates() {
		this.detectionStateRepository.deleteAll();
	}

	@Test
	@Transactional
	void returnsTheLockedRowForTheIdentity() {
		this.detectionStateRepository.saveAndFlush(state(SERIES, IncidentType.ERROR_RATE));

		Optional<DetectionState> locked = findForUpdate(SERIES, IncidentType.ERROR_RATE);

		assertThat(locked).get().satisfies((state) -> {
			assertThat(state.getService()).isEqualTo(SERVICE);
			assertThat(state.getMethod()).isEqualTo(METHOD);
			assertThat(state.getEndpoint()).isEqualTo(ENDPOINT);
			assertThat(state.getType()).isEqualTo(IncidentType.ERROR_RATE);
		});
	}

	/** 같은 계열이라도 종류마다 다른 행이므로 서로의 잠금과 연속 횟수에 영향을 주지 않는다. */
	@Test
	@Transactional
	void looksUpEachTypeSeparately() {
		DetectionState errorRate = state(SERIES, IncidentType.ERROR_RATE);
		errorRate.apply(FIRST_WINDOW_END, WindowVerdict.abnormal(IncidentSeverity.WARNING));
		this.detectionStateRepository.saveAndFlush(errorRate);
		this.detectionStateRepository.saveAndFlush(state(SERIES, IncidentType.LATENCY));

		Optional<DetectionState> locked = findForUpdate(SERIES, IncidentType.LATENCY);

		assertThat(locked).get().satisfies((state) -> {
			assertThat(state.getType()).isEqualTo(IncidentType.LATENCY);
			assertThat(state.getAbnormalConsecutive()).isZero();
		});
	}

	/** 계열이 다르면 잠글 행도 다르다. method만 달라도 서로 다른 자리이다. */
	@Test
	@Transactional
	void looksUpEachSeriesSeparately() {
		this.detectionStateRepository.saveAndFlush(state(SERIES, IncidentType.ERROR_RATE));

		Optional<DetectionState> otherMethod = findForUpdate(new MetricSeries(SERVICE, "POST", ENDPOINT),
				IncidentType.ERROR_RATE);

		assertThat(otherMethod).isEmpty();
	}

	/**
	 * 아직 상태가 없는 계열에는 잠글 행이 없다. 이 빈 결과가 곧 최초 생성 경쟁이 잠금으로 막히지
	 * 않는 지점이며, 그 자리를 막는 것은 identity UNIQUE이다.
	 */
	@Test
	@Transactional
	void findsNothingToLockBeforeTheFirstStateExists() {
		assertThat(findForUpdate(SERIES, IncidentType.ERROR_RATE)).isEmpty();
	}

	/** 잠금은 Transaction의 범위만큼만 유지되므로 Transaction 밖의 잠금 조회는 뜻이 없다. */
	@Test
	void refusesToLockOutsideATransaction() {
		this.detectionStateRepository.saveAndFlush(state(SERIES, IncidentType.ERROR_RATE));

		assertThatThrownBy(() -> findForUpdate(SERIES, IncidentType.ERROR_RATE))
			.isInstanceOf(InvalidDataAccessApiUsageException.class)
			.hasRootCauseInstanceOf(TransactionRequiredException.class);
	}

	private Optional<DetectionState> findForUpdate(MetricSeries series, IncidentType type) {
		return this.detectionStateRepository.findForUpdate(series.service(), series.method(), series.endpoint(), type);
	}

	private static DetectionState state(MetricSeries series, IncidentType type) {
		return DetectionState.initialize(series, type, FIRST_WINDOW_END);
	}

}

package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.metric.MetricSeries;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Detection State가 실제 MySQL에 어떤 값으로 저장되고 어떤 값으로 돌아오는지 검증한다.
 * <p>
 * Context가 뜨는 것 자체가 첫 번째 검증이다. {@code ddl-auto=validate}가 V2로 만든 detection_state
 * table과 Entity mapping을 대조하므로, 열 이름이나 길이, null 허용이 어긋나면 이 Class의 모든 테스트가
 * 기동 단계에서 실패한다.
 * <p>
 * 이 상태가 MySQL에 있는 이유는 재시작을 견디기 위해서다. 그래서 값이 돌아오는지만 보지 않고, 저장한
 * 상태를 다시 읽어 이어서 세었을 때 연속이 유지되는지까지 본다.
 * <p>
 * Transaction 없이 Repository를 호출하므로 저장 뒤의 조회는 1차 Cache가 아니라 실제 SELECT이다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class DetectionStatePersistenceIntegrationTest {

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "GET";

	private static final String ENDPOINT = "/api/orders/{orderId}";

	private static final MetricSeries SERIES = new MetricSeries(SERVICE, METHOD, ENDPOINT);

	/** DATETIME(6)까지 남는지 보려고 마이크로초 자리를 채운다. 실제 창의 끝은 Bucket 경계이다. */
	private static final Instant FIRST_WINDOW_END = Instant.parse("2026-08-31T12:00:30.123456Z");

	private static final Instant SECOND_WINDOW_END = Instant.parse("2026-08-31T12:00:40.123456Z");

	@Autowired
	private DetectionStateRepository detectionStateRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void removeDetectionStates() {
		this.detectionStateRepository.deleteAll();
	}

	@Test
	void storesAndReloadsEveryField() {
		DetectionState state = DetectionState.initialize(SERIES, IncidentType.ERROR_RATE, FIRST_WINDOW_END);
		state.apply(FIRST_WINDOW_END, WindowVerdict.abnormal(IncidentSeverity.CRITICAL));

		DetectionState reloaded = reload(this.detectionStateRepository.saveAndFlush(state).getId());

		assertThat(reloaded.getService()).isEqualTo(SERVICE);
		assertThat(reloaded.getMethod()).isEqualTo(METHOD);
		assertThat(reloaded.getEndpoint()).isEqualTo(ENDPOINT);
		assertThat(reloaded.getType()).isEqualTo(IncidentType.ERROR_RATE);
		assertThat(reloaded.getLastEvaluatedWindowEnd()).isEqualTo(FIRST_WINDOW_END);
		assertThat(reloaded.getAbnormalConsecutive()).isEqualTo(1);
		assertThat(reloaded.getRecoveryConsecutive()).isZero();
		assertThat(reloaded.getUpdatedAt()).isEqualTo(FIRST_WINDOW_END);
	}

	/**
	 * 재시작을 견디는 것이 이 table의 존재 이유이다. 저장된 상태를 다시 읽어 다음 창을 이어서 세었을
	 * 때 연속이 유지되지 않으면, 재시작 시점에 진행 중이던 이상은 영원히 기준에 닿지 못한다.
	 */
	@Test
	void continuesTheSequenceAfterBeingReloaded() {
		DetectionState state = DetectionState.initialize(SERIES, IncidentType.ERROR_RATE, FIRST_WINDOW_END);
		state.apply(FIRST_WINDOW_END, WindowVerdict.abnormal(IncidentSeverity.WARNING));
		Long id = this.detectionStateRepository.saveAndFlush(state).getId();

		DetectionState reloaded = reload(id);
		reloaded.apply(SECOND_WINDOW_END, WindowVerdict.abnormal(IncidentSeverity.WARNING));
		this.detectionStateRepository.saveAndFlush(reloaded);

		DetectionState afterSecondWindow = reload(id);
		assertThat(afterSecondWindow.getAbnormalConsecutive()).isEqualTo(2);
		assertThat(afterSecondWindow.getLastEvaluatedWindowEnd()).isEqualTo(SECOND_WINDOW_END);
	}

	/** ordinal로 저장되면 값의 순서를 바꾸는 순간 과거 기록의 뜻이 통째로 달라진다. */
	@Test
	void storesTheTypeAsItsName() {
		Long id = save(IncidentType.LATENCY).getId();

		assertThat(storedValueOf(id, "type")).isEqualTo("LATENCY");
	}

	/**
	 * 저장된 시각이 JVM 시간대로 옮겨지지 않았는지 SQL로 직접 본다. 이 값이 시간대만큼 어긋나면 다음
	 * 창이 연속인지 판단하는 기준 자체가 틀어진다.
	 */
	@Test
	void storesTimesAsUtcWithMicrosecondPrecision() {
		DetectionState state = DetectionState.initialize(SERIES, IncidentType.ERROR_RATE, FIRST_WINDOW_END);
		state.apply(FIRST_WINDOW_END, WindowVerdict.healthy());
		Long id = this.detectionStateRepository.saveAndFlush(state).getId();

		assertThat(storedValueOf(id, "last_evaluated_window_end")).isEqualTo("2026-08-31 12:00:30.123456");
		assertThat(storedValueOf(id, "updated_at")).isEqualTo("2026-08-31 12:00:30.123456");
		assertThat(reload(id).getLastEvaluatedWindowEnd()).isEqualTo(FIRST_WINDOW_END);
	}

	/** 아직 아무 창도 평가하지 않은 상태도 그대로 저장된다. cursor는 첫 창의 한 Bucket 앞이다. */
	@Test
	void storesAStateThatHasNotEvaluatedAnyWindowYet() {
		Long id = save(IncidentType.ERROR_RATE).getId();

		DetectionState reloaded = reload(id);

		assertThat(reloaded.getLastEvaluatedWindowEnd()).isEqualTo(Instant.parse("2026-08-31T12:00:20.123456Z"));
		assertThat(reloaded.getAbnormalConsecutive()).isZero();
		assertThat(reloaded.getRecoveryConsecutive()).isZero();
	}

	@Test
	void createsTheDetectionStateTableWithTheDesignedColumns() {
		List<String> columnNames = this.jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns "
				+ "WHERE table_schema = DATABASE() AND table_name = 'detection_state' ORDER BY ordinal_position",
				String.class);

		assertThat(columnNames).containsExactly("id", "service", "method", "endpoint", "type",
				"last_evaluated_window_end", "abnormal_consecutive", "recovery_consecutive", "updated_at");
	}

	/** identity UNIQUE가 곧 조회용 index이다. 같은 열로 index를 또 만들면 쓰기마다 두 번 갱신된다. */
	@Test
	void indexesTheIdentityAsUniqueAndAddsNoOtherIndex() {
		assertThat(indexColumnsOf("uk_detection_state_series_type")).containsExactly("service", "method", "endpoint",
				"type");
		assertThat(this.jdbcTemplate.queryForList("SELECT DISTINCT index_name FROM information_schema.statistics "
				+ "WHERE table_schema = DATABASE() AND table_name = 'detection_state'", String.class))
			.containsExactlyInAnyOrder("PRIMARY", "uk_detection_state_series_type");
	}

	/**
	 * Entity는 음수 counter를 만들 수 없지만 이 table을 고치는 경로가 Entity 하나뿐이라는 보장은
	 * 없으므로 DB에도 같은 제약을 둔다. 어느 하위 Type으로 번역될지는 Driver에 달려 있어 상위 Type으로
	 * 확인한다.
	 */
	@Test
	void rejectsNegativeCounters() {
		assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> insertState("POST", -1, 0));
		assertThatExceptionOfType(DataAccessException.class).isThrownBy(() -> insertState("POST", 0, -1));
	}

	/** 상태가 둘로 갈라지면 연속 횟수가 나뉘어 어느 쪽도 기준에 닿지 못한다. */
	@Test
	void rejectsASecondStateForTheSameIdentity() {
		save(IncidentType.ERROR_RATE);

		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> save(IncidentType.ERROR_RATE));

		assertThat(this.detectionStateRepository.count()).isEqualTo(1);
	}

	/** 오류율과 응답 시간은 나빠지는 시점이 달라 같은 계열에서도 연속을 따로 센다. */
	@Test
	void keepsOneStatePerType() {
		save(IncidentType.ERROR_RATE);
		save(IncidentType.LATENCY);

		assertThat(this.detectionStateRepository.count()).isEqualTo(2);
	}

	/** 계열은 method와 endpoint까지 포함하므로 어느 하나만 달라도 서로 다른 상태이다. */
	@Test
	void keepsOneStatePerMethodAndEndpoint() {
		save(new MetricSeries(SERVICE, METHOD, ENDPOINT), IncidentType.ERROR_RATE);
		save(new MetricSeries(SERVICE, "POST", ENDPOINT), IncidentType.ERROR_RATE);
		save(new MetricSeries(SERVICE, METHOD, "/api/payments"), IncidentType.ERROR_RATE);

		assertThat(this.detectionStateRepository.count()).isEqualTo(3);
	}

	private DetectionState save(IncidentType type) {
		return save(SERIES, type);
	}

	private DetectionState save(MetricSeries series, IncidentType type) {
		return this.detectionStateRepository.saveAndFlush(DetectionState.initialize(series, type, FIRST_WINDOW_END));
	}

	private void insertState(String method, int abnormalConsecutive, int recoveryConsecutive) {
		this.jdbcTemplate.update("""
				INSERT INTO detection_state (service, method, endpoint, type, last_evaluated_window_end,
						abnormal_consecutive, recovery_consecutive, updated_at)
				VALUES (?, ?, ?, 'ERROR_RATE', '2026-08-31 12:00:20.123456', ?, ?, '2026-08-31 12:00:20.123456')
				""", SERVICE, method, ENDPOINT, abnormalConsecutive, recoveryConsecutive);
	}

	private DetectionState reload(Long id) {
		return this.detectionStateRepository.findById(id).orElseThrow();
	}

	/** 값을 Java Type으로 되돌리지 않고 저장된 그대로 읽는다. */
	private String storedValueOf(Long id, String column) {
		return this.jdbcTemplate.queryForObject("SELECT " + column + " FROM detection_state WHERE id = ?", String.class,
				id);
	}

	private List<String> indexColumnsOf(String indexName) {
		return this.jdbcTemplate.queryForList("SELECT column_name FROM information_schema.statistics "
				+ "WHERE table_schema = DATABASE() AND table_name = 'detection_state' AND index_name = ? "
				+ "ORDER BY seq_in_index", String.class, indexName);
	}

}

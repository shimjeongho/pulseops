package com.pulseops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * PulseOps가 실제 MySQL 서버 위에서 Flyway로 Schema를 올리고 기동하는지 검증한다.
 * <p>
 * Phase 4-1은 Incident 기능을 만들지 않고 영속성 기반만 준비하는 단계이므로, 검증 대상은 Java
 * 코드가 아니라 Migration이 실제 MySQL에 남긴 결과이다. Entity가 아직 없어 Repository나 JPA
 * mapping은 확인하지 않는다.
 * <p>
 * active_key는 Application이 채우지 않고 MySQL이 다른 열에서 파생시키는 값이라, 열이 존재하는지만
 * 봐서는 파생 규칙이 맞는지 알 수 없다. 그래서 구조뿐 아니라 실제로 행을 넣어 값과 제약을 함께
 * 확인한다. 행 삽입은 JDBC로 직접 하며, 이 단계에는 Incident Entity가 없다.
 * <p>
 * Kafka Listener는 시작하지 않는다. Schema를 보는 테스트가 Broker 유무에 좌우되면 안 된다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class MySqlTestcontainersIntegrationTest {

	/** 이 테스트가 만든 행만 지우기 위한 표식이다. 실제 Demo Shop service 이름을 쓰지 않는다. */
	private static final String TEST_SERVICE = "mysql-bootstrap-test";

	private static final String ENDPOINT = "/api/orders/{orderId}";

	private static final String TYPE = "ERROR_RATE";

	private static final String WINDOW_START = "2026-08-31 12:34:00.000000";

	private static final String WINDOW_END = "2026-08-31 12:35:00.000000";

	/** MySQL의 기본 Port이다. 테스트가 개발자의 로컬 MySQL을 건드리지 않는지 확인하는 데 쓴다. */
	private static final int MYSQL_DEFAULT_PORT = 3306;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MySQLContainer mysqlContainer;

	@AfterEach
	void removeRowsCreatedByThisTest() {
		this.jdbcTemplate.update("DELETE FROM incident WHERE service = ?", TEST_SERVICE);
	}

	@Test
	void connectsToTheMySqlContainerOnAMappedPort() throws Exception {
		try (Connection connection = this.dataSource.getConnection()) {
			DatabaseMetaData metaData = connection.getMetaData();

			assertThat(metaData.getDatabaseProductName()).isEqualTo("MySQL");
			assertThat(metaData.getDatabaseProductVersion()).startsWith("8.4");
			assertThat(metaData.getURL()).contains(":" + this.mysqlContainer.getFirstMappedPort());
		}
	}

	/** 임의 Port를 쓴다. 기본 Port를 쓰면 개발자의 로컬 MySQL에 테스트가 쓰고 지우게 된다. */
	@Test
	void doesNotUseTheDefaultMySqlPort() {
		assertThat(this.mysqlContainer.getFirstMappedPort()).isNotEqualTo(MYSQL_DEFAULT_PORT);
	}

	@Test
	void appliesEveryMigrationWithFlyway() {
		List<String> appliedScripts = this.jdbcTemplate.queryForList(
				"SELECT script FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank", String.class);

		assertThat(appliedScripts).containsExactly("V1__create_incident_table.sql",
				"V2__create_detection_state_table.sql");
	}

	@Test
	void createsTheIncidentTableWithTheDesignedColumns() {
		assertThat(columnNamesOfIncident()).containsExactly("id", "service", "method", "endpoint", "type", "severity",
				"status", "opened_at", "acknowledged_at", "resolved_at", "updated_at", "trigger_window_start",
				"trigger_window_end", "trigger_request_count", "trigger_error_count", "trigger_error_rate",
				"trigger_p95_latency_ms", "active_key");
	}

	@Test
	void derivesActiveKeyAsAStoredGeneratedColumn() {
		String extra = this.jdbcTemplate.queryForObject("SELECT extra FROM information_schema.columns "
				+ "WHERE table_schema = DATABASE() AND table_name = 'incident' AND column_name = 'active_key'",
				String.class);

		assertThat(extra).isEqualTo("STORED GENERATED");
	}

	/**
	 * 처리 중인 Incident에만 값이 생기고 RESOLVED가 되면 사라져야 한다. 이 두 가지가 곧 "활성
	 * Incident는 계열마다 하나"라는 제약이 걸리는 범위이다.
	 */
	@Test
	void derivesActiveKeyOnlyWhileTheIncidentIsActive() {
		long openId = insertIncident("GET", "OPEN");
		long resolvedId = insertIncident("POST", "RESOLVED");

		assertThat(activeKeyOf(openId)).isEqualTo(TEST_SERVICE + "|GET|" + ENDPOINT + "|" + TYPE);
		assertThat(activeKeyOf(resolvedId)).isNull();

		this.jdbcTemplate.update("UPDATE incident SET status = 'RESOLVED' WHERE id = ?", openId);

		assertThat(activeKeyOf(openId)).isNull();
	}

	@Test
	void rejectsASecondActiveIncidentForTheSameIdentity() {
		insertIncident("GET", "OPEN");

		assertThatExceptionOfType(DuplicateKeyException.class)
			.isThrownBy(() -> insertIncident("GET", "ACKNOWLEDGED"));
	}

	/**
	 * RESOLVED는 이력이므로 같은 계열에 얼마든지 쌓이고, 활성 자리를 비우면 다음 Incident를 다시
	 * 열 수 있어야 한다. UNIQUE Index가 NULL을 서로 다른 값으로 보기 때문에 성립한다.
	 */
	@Test
	void allowsManyResolvedIncidentsAndANewActiveOneForTheSameIdentity() {
		long firstId = insertIncident("GET", "OPEN");
		insertIncident("GET", "RESOLVED");
		insertIncident("GET", "RESOLVED");

		this.jdbcTemplate.update("UPDATE incident SET status = 'RESOLVED' WHERE id = ?", firstId);

		assertThat(insertIncident("GET", "OPEN")).isPositive();
	}

	@Test
	void indexesTheActiveKeyAsUniqueAndTheSeriesLookup() {
		assertThat(indexColumnsOf("uk_incident_active")).containsExactly("active_key");
		assertThat(isUniqueIndex("uk_incident_active")).isTrue();

		assertThat(indexColumnsOf("idx_incident_series")).containsExactly("service", "method", "endpoint", "type",
				"status");
		assertThat(isUniqueIndex("idx_incident_series")).isFalse();
	}

	/**
	 * CHECK 위반이 Spring의 MySQL Error Code 표에 없어 어떤 하위 Type으로 번역될지는 Driver와
	 * 버전에 달려 있다. 여기서 고정하려는 계약은 "거부된다"이므로 상위 Type으로 확인한다.
	 */
	@Test
	void rejectsTriggerValuesOutsideTheAllowedRange() {
		assertThatExceptionOfType(DataAccessException.class)
			.isThrownBy(() -> insertIncident("GET", "OPEN", "150.00", 120, 18));
		assertThatExceptionOfType(DataAccessException.class)
			.isThrownBy(() -> insertIncident("GET", "OPEN", "15.00", -1, 18));
		assertThatExceptionOfType(DataAccessException.class)
			.isThrownBy(() -> insertIncident("GET", "OPEN", "15.00", 120, -1));
	}

	private long insertIncident(String method, String status) {
		return insertIncident(method, status, "15.00", 120, 18);
	}

	/**
	 * 시각은 문자열로 넣는다. 이 단계의 확인 대상은 Schema이고, Java 시각 Type을 거치면 JDBC 연결의
	 * 시간대 변환까지 함께 보게 된다.
	 */
	private long insertIncident(String method, String status, String errorRate, long requestCount, long errorCount) {
		this.jdbcTemplate.update("""
				INSERT INTO incident (service, method, endpoint, type, severity, status, opened_at, updated_at,
						trigger_window_start, trigger_window_end, trigger_request_count, trigger_error_count,
						trigger_error_rate, trigger_p95_latency_ms)
				VALUES (?, ?, ?, ?, 'WARNING', ?, ?, ?, ?, ?, ?, ?, ?, 240)
				""", TEST_SERVICE, method, ENDPOINT, TYPE, status, WINDOW_END, WINDOW_END, WINDOW_START, WINDOW_END,
				requestCount, errorCount, errorRate);
		return this.jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private String activeKeyOf(long incidentId) {
		return this.jdbcTemplate.queryForObject("SELECT active_key FROM incident WHERE id = ?", String.class,
				incidentId);
	}

	private List<String> columnNamesOfIncident() {
		return this.jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns "
				+ "WHERE table_schema = DATABASE() AND table_name = 'incident' ORDER BY ordinal_position",
				String.class);
	}

	private List<String> indexColumnsOf(String indexName) {
		return this.jdbcTemplate.queryForList("SELECT column_name FROM information_schema.statistics "
				+ "WHERE table_schema = DATABASE() AND table_name = 'incident' AND index_name = ? "
				+ "ORDER BY seq_in_index", String.class, indexName);
	}

	private boolean isUniqueIndex(String indexName) {
		Integer nonUnique = this.jdbcTemplate.queryForObject("SELECT DISTINCT non_unique "
				+ "FROM information_schema.statistics "
				+ "WHERE table_schema = DATABASE() AND table_name = 'incident' AND index_name = ?", Integer.class,
				indexName);
		return Integer.valueOf(0).equals(nonUnique);
	}

}

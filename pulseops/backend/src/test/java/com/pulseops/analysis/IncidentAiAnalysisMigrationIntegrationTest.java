package com.pulseops.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 이미 Phase 9 분석이 들어 있는 DB에 새 migration을 적용했을 때 그 기록이 어떻게 되는지를 확인한다.
 * <p>
 * 빈 DB에 전부 적용해 보는 것만으로는 부족하다. 다른 테스트들은 전부 빈 상태에서 시작하므로, 기존 행을
 * 채우는 UPDATE와 UNIQUE를 바꾸는 구문이 실제 데이터 위에서 어떻게 동작하는지는 그 어디에서도 확인되지
 * 않는다. migration이 잘못되었을 때 드러나는 시점은 운영 DB에 적용하는 순간이다.
 * <p>
 * 여기서 지켜야 할 것은 하나다. 이미 만들어진 분석의 어떤 값도 바뀌지 않는다. 그 행들은 실제로 Incident
 * 근거만 보고 만들어졌으므로 종류를 채우는 것은 새로 정하는 사실이 아니라 이미 참인 사실을 적는 일이며,
 * 그 밖의 열은 한 글자도 달라져서는 안 된다.
 * <p>
 * Spring Context를 띄우지 않고 Flyway를 직접 부른다. 확인하려는 것이 Application의 동작이 아니라 migration
 * 그 자체이고, 판을 나눠 적용하려면 Flyway를 우리가 들고 있어야 한다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class IncidentAiAnalysisMigrationIntegrationTest {

	private static final String MYSQL_IMAGE = "mysql:8.4.11";

	/** Phase 9까지의 판이다. 이 시점의 Schema에 기존 데이터를 넣고 나서 나머지를 적용한다. */
	private static final String PHASE_NINE_VERSION = "4";

	private static final String SUMMARY = "예전에 저장된 요약이다.";

	private static final String MODEL = "claude-opus-5-20260101";

	private static final String INPUT_SNAPSHOT = "{\"service\":\"demo-shop\",\"endpoint\":\"/api/orders\"}";

	@Container
	private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse(MYSQL_IMAGE));

	/**
	 * 테스트마다 깨끗한 Schema에서 시작한다. 하나의 Container를 나눠 쓰므로, 앞선 테스트가 남긴 판과
	 * 데이터가 그대로 있으면 어느 테스트가 무엇을 확인했는지 알 수 없게 된다.
	 */
	@BeforeEach
	void resetSchema() {
		flyway().load().clean();
	}

	/**
	 * 기존 분석은 그대로 남고 종류만 채워진다.
	 * <p>
	 * summary와 model과 prompt 판과 snapshot을 하나씩 확인한다. 그 넷이 이 기록의 내용 전부이며, 하나라도
	 * 달라지면 그 분석이 무엇을 보고 무엇이라고 말했는지가 바뀐 것이다.
	 */
	@Test
	void backfillsAnExistingAnalysisAsIncidentOnlyWithoutTouchingAnythingElse() throws SQLException {
		migrateTo(PHASE_NINE_VERSION);
		insertIncident();
		insertPhaseNineAnalysis();

		migrateToLatest();

		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			ResultSet analysis = statement.executeQuery(
					"SELECT analysis_type, github_context_id, summary, model, prompt_version, input_snapshot,"
							+ " confidence FROM incident_ai_analysis");
			assertThat(analysis.next()).isTrue();
			assertThat(analysis.getString("analysis_type")).isEqualTo("INCIDENT_ONLY");
			assertThat(analysis.getObject("github_context_id")).isNull();
			assertThat(analysis.getString("summary")).isEqualTo(SUMMARY);
			assertThat(analysis.getString("model")).isEqualTo(MODEL);
			assertThat(analysis.getString("prompt_version")).isEqualTo(IncidentAnalysisPrompt.VERSION);
			assertThat(analysis.getString("input_snapshot")).isEqualTo(INPUT_SNAPSHOT);
			assertThat(analysis.getString("confidence")).isEqualTo("MEDIUM");
			assertThat(analysis.next()).isFalse();
		}
	}

	/**
	 * 종류를 채운 뒤에는 비어 있을 수 없다. 순서를 뒤집어 값을 채우기 전에 NOT NULL을 걸면 기존 행이
	 * 걸려 migration 자체가 실패한다.
	 */
	@Test
	void requiresEveryAnalysisToSayWhatItLookedAt() throws SQLException {
		migrateToLatest();

		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			ResultSet column = statement.executeQuery("SELECT is_nullable FROM information_schema.columns"
					+ " WHERE table_schema = DATABASE() AND table_name = 'incident_ai_analysis'"
					+ " AND column_name = 'analysis_type'");
			assertThat(column.next()).isTrue();
			assertThat(column.getString("is_nullable")).isEqualTo("NO");
		}
	}

	/**
	 * Incident 하나에 종류마다 분석이 하나씩이다. 이 UNIQUE가 없으면 같은 종류의 분석이 두 행으로 쌓이고,
	 * 사람은 그중 어느 것을 읽어야 하는지 정할 방법이 없다.
	 */
	@Test
	void allowsTwoAnalysesOfDifferentKindsButNotTwoOfTheSame() throws SQLException {
		migrateToLatest();
		insertIncident();

		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			statement.executeUpdate(insertAnalysis("INCIDENT_ONLY", "판정 근거만 본 요약이다."));
			statement.executeUpdate(insertAnalysis("GITHUB_ENRICHED", "코드까지 본 요약이다."));

			assertThatExceptionOfType(SQLIntegrityConstraintViolationException.class)
				.isThrownBy(() -> statement.executeUpdate(insertAnalysis("GITHUB_ENRICHED", "또 다른 요약이다.")));
		}
	}

	/**
	 * 코드 기반 분석이 가리키는 맥락은 실제로 존재해야 한다. 없는 맥락을 가리키면 그 분석이 무엇을 보고
	 * 만들어졌는지 되짚을 수 없다.
	 */
	@Test
	void refusesToPointAtAContextThatDoesNotExist() throws SQLException {
		migrateToLatest();
		insertIncident();

		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			statement.executeUpdate(insertAnalysis("INCIDENT_ONLY", "판정 근거만 본 요약이다."));

			assertThatExceptionOfType(SQLException.class)
				.isThrownBy(() -> statement.executeUpdate("UPDATE incident_ai_analysis SET github_context_id = 999999"));
		}
	}

	private void migrateTo(String version) {
		flyway().target(version).load().migrate();
	}

	private void migrateToLatest() {
		flyway().load().migrate();
	}

	private FluentConfiguration flyway() {
		return Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.cleanDisabled(false);
	}

	private void insertIncident() throws SQLException {
		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					INSERT INTO incident (id, service, method, endpoint, type, severity, status, opened_at, updated_at,
						trigger_window_start, trigger_window_end, trigger_request_count, trigger_error_count,
						trigger_error_rate)
					VALUES (1, 'demo-shop', 'POST', '/api/orders', 'ERROR_RATE', 'CRITICAL', 'OPEN',
						'2026-09-01 06:00:00', '2026-09-01 06:00:00', '2026-09-01 05:59:00', '2026-09-01 06:00:00',
						170, 140, 82.35)
					""");
		}
	}

	/** Phase 9 시점의 열 구성으로 넣는다. 그때는 종류라는 열이 없었다. */
	private void insertPhaseNineAnalysis() throws SQLException {
		try (Connection connection = connect(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					INSERT INTO incident_ai_analysis (incident_id, model, prompt_version, input_snapshot, summary,
						primary_hypothesis, confidence, evidence, recommended_actions, limitations, generated_at)
					VALUES (1, '%s', '%s', '%s', '%s', '의존 서비스의 실패가 의심된다.', 'MEDIUM',
						'["오류율이 82.35%%였다."]', '["로그를 확인한다."]', '["log가 주어지지 않았다."]',
						'2026-09-01 06:10:00')
					""".formatted(MODEL, IncidentAnalysisPrompt.VERSION, INPUT_SNAPSHOT, SUMMARY));
		}
	}

	private static String insertAnalysis(String analysisType, String summary) {
		return """
				INSERT INTO incident_ai_analysis (incident_id, analysis_type, model, prompt_version, input_snapshot,
					summary, primary_hypothesis, confidence, evidence, recommended_actions, limitations, generated_at)
				VALUES (1, '%s', '%s', '%s', '%s', '%s', '가설이다.', 'LOW', '["근거"]', '["확인"]', '["한계"]',
					'2026-09-01 06:20:00')
				""".formatted(analysisType, MODEL, IncidentCodeAnalysisPrompt.VERSION, INPUT_SNAPSHOT, summary);
	}

	private static Connection connect() throws SQLException {
		return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
	}

}

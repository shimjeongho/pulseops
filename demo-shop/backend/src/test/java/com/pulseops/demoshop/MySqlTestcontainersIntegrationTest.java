package com.pulseops.demoshop;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers 기반 MySQL 테스트 환경이 동작하는지 검증한다. 애플리케이션이 실제 MySQL
 * 서버에 연결하여 쿼리를 실행할 수 있어야 한다.
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "pulseops.telemetry.enabled=false")
class MySqlTestcontainersIntegrationTest {

	@Autowired
	private DataSource dataSource;

	@Test
	void connectsToMySqlContainer() throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");

			try (Statement statement = connection.createStatement();
					ResultSet resultSet = statement.executeQuery("SELECT 1")) {
				assertThat(resultSet.next()).isTrue();
				assertThat(resultSet.getInt(1)).isEqualTo(1);
			}
		}
	}

}

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
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies that the Testcontainers MySQL test environment works: the application
 * connects to a real MySQL server and can execute a query.
 * Requires Docker; skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
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

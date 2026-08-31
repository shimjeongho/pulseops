package com.pulseops;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PulseOps Context에 실제 MySQL을 연결한다.
 * <p>
 * Redis, Kafka Container와 달리 이 구성은 여러 테스트가 함께 쓴다. Datasource가 생긴 뒤에는
 * {@code @SpringBootTest} 전부가 시작할 때 Flyway를 실행하므로, MySQL을 쓰지 않는 테스트에도
 * Datasource가 필요해진다. Auto Configuration을 꺼서 피하지 않는다. 그렇게 하면 테스트가 확인하는
 * Context가 Production과 달라진다.
 * <p>
 * Port는 Testcontainers가 정하는 임의 Port이며 로컬 3306을 쓰지 않는다. 개발자의 로컬 MySQL에
 * 테스트가 쓰고 지우는 일이 생기면 안 된다.
 * <p>
 * Redis, Kafka와 마찬가지로 Image tag를 patch까지 고정한다. tag가 움직이면 재현되지 않는 테스트가
 * 된다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestcontainersConfiguration {

	private static final String MYSQL_IMAGE = "mysql:8.4.11";

	@Bean
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		return new MySQLContainer(DockerImageName.parse(MYSQL_IMAGE));
	}

}

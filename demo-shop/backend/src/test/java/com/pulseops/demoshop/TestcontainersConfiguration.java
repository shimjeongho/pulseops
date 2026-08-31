package com.pulseops.demoshop;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 공용 테스트 인프라이다. {@link ServiceConnection}으로 MySQL Container를 Spring Context에
 * 연결하여 Integration Test가 In-memory 대체재가 아닌 실제 MySQL을 사용하도록 한다.
 * 데이터베이스가 필요한 테스트에서 이 설정을 Import한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		return new MySQLContainer(DockerImageName.parse("mysql:8.4"));
	}

}

package com.pulseops.demoshop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Spring Application Context가 정상적으로 기동되는지 검증한다.
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "pulseops.telemetry.enabled=false")
class DemoShopBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}

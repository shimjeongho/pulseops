package com.pulseops.demoshop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies that the Spring application context starts.
 * Requires Docker; skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DemoShopBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}

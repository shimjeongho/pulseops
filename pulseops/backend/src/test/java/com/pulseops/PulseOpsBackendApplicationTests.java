package com.pulseops;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Spring Application Context가 정상적으로 기동되는지 검증한다.
 * PulseOps Backend는 Web Server 없이 기동된다.
 * <p>
 * Kafka Listener는 시작하지 않는다. Context 기동 여부만 보는 테스트가 Broker 연결에 좌우되면
 * 안 되기 때문이다. 실제 Broker 연동은 Phase 2-6 Integration Test에서 확인한다.
 * <p>
 * 반면 MySQL은 띄운다. Phase 4-1에서 Datasource가 생겨 기동 자체가 DB 연결과 Flyway 실행을
 * 포함하게 되었고, 그것이 기동되는지가 바로 이 테스트가 보는 것이다.
 * {@link MySqlTestcontainersConfiguration} 참고.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class PulseOpsBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}

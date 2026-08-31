package com.pulseops;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Spring Application Context가 정상적으로 기동되는지 검증한다.
 * PulseOps Backend는 Web Server와 외부 Infrastructure 없이 기동된다.
 * <p>
 * Kafka Listener는 시작하지 않는다. Context 기동 여부만 보는 테스트가 Broker 연결에 좌우되면
 * 안 되기 때문이다. 실제 Broker 연동은 Phase 2-6 Integration Test에서 확인한다.
 */
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class PulseOpsBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}

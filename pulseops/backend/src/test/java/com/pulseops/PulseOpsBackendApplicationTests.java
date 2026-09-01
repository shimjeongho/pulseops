package com.pulseops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Spring Application Context가 정상적으로 기동되는지 검증한다.
 * PulseOps Backend는 Phase 5-1부터 Servlet Web Application으로 기동된다.
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

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void contextLoads() {
	}

	/**
	 * Web 계층이 붙었다는 사실을 Dependency 선언이 아니라 기동 결과로 확인한다. Web Starter가
	 * 빠지면 Context Type이 조용히 되돌아가고, 그때 깨지는 것은 이 단계가 아니라 REST와 SSE를
	 * 올리는 이후 단계이다.
	 */
	@Test
	void startsAsAServletWebApplication() {
		assertThat(this.applicationContext).isInstanceOf(WebApplicationContext.class);
	}

}

package com.pulseops.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseops.MySqlTestcontainersConfiguration;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka에 붙지 않는 구성에서 Consumer Lag 조회가 어떻게 답하는지를 고정한다.
 * <p>
 * 가짜 Client를 세우지 않는다. 그것이 이 테스트의 요점이다. Client bean이 아예 없는 상태를 만들어야
 * "지금 lag을 읽을 수 있는가"가 어느 method 안의 분기가 아니라 Application의 구성이라는 것을 확인할 수
 * 있다. 그리고 그 구성에서도 Application은 기동해야 하고 나머지 화면은 그대로 동작해야 한다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class ConsumerLagDisabledIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * Admin Client를 만들어 두지 않는다. 만들어 두면 Broker가 없는 환경에서 그 Client의 Thread가 닿지
	 * 않는 주소로 연결을 계속 시도한다.
	 */
	@Test
	void startsWithoutAnyKafkaAdminClient() {
		assertThat(this.applicationContext.getBeansOfType(KafkaConsumerLagClient.class)).isEmpty();
		assertThat(this.applicationContext.getBeansOfType(Admin.class)).isEmpty();
	}

	/** 읽을 수 없다는 것을 "밀린 것이 없다"로 답하지 않는다. */
	@Test
	void refusesToAnswerAndSaysNothingAboutTheServerConfiguration() throws Exception {
		JsonNode body = this.objectMapper.readTree(this.mockMvc.perform(get("/api/kafka/consumer-lag"))
			.andExpect(status().isServiceUnavailable())
			.andReturn()
			.getResponse()
			.getContentAsString());

		assertThat(body.get("code").stringValue()).isEqualTo("KAFKA_LAG_UNAVAILABLE");
		assertThat(body.get("totalLag")).isNull();
		assertThat(this.objectMapper.writeValueAsString(body)).doesNotContain("auto-startup", "listener", "disabled");
	}

	/** lag을 읽지 못하는 것이 Metric이나 Incident 화면을 함께 멈추지 않는다. */
	@Test
	void leavesTheRestOfTheApplicationWorking() throws Exception {
		this.mockMvc.perform(get("/api/incidents/active")).andExpect(status().isOk());
	}

}

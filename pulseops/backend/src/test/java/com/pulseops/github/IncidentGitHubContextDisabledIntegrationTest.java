package com.pulseops.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.incident.Incident;
import com.pulseops.incident.IncidentRepository;
import com.pulseops.incident.IncidentSeverity;
import com.pulseops.incident.IncidentType;
import com.pulseops.metric.MetricSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
 * GitHub 연동이 꺼져 있는 기본 상태에서 무엇이 되고 무엇이 안 되는지를 고정한다.
 * <p>
 * 여기가 이 Application의 기본 모습이다. token이 없는 환경에서도 기동하고, 나머지 기능은 전부 동작하며,
 * 수집만 거절된다. 그 성질이 깨지면 credential이 없는 곳에서 PulseOps 전체를 띄울 수 없게 된다.
 * <p>
 * 가짜 Client를 세우지 않는다. 그것이 이 테스트의 요점이다. bean이 아예 없는 상태를 만들어야 "기능이
 * 꺼져 있다"가 어느 method 안의 분기가 아니라 Application의 구조라는 것을 확인할 수 있다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentGitHubContextDisabledIntegrationTest {

	private static final Instant WINDOW_START = Instant.parse("2026-09-01T05:59:00Z");

	private static final Instant OPENED_AT = Instant.parse("2026-09-01T06:00:00Z");

	private static final String CONTROLLER_PATH = "demo-shop/backend/src/main/java/OrderController.java";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private IncidentGitHubContextRepository incidentGitHubContextRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@AfterEach
	void removeEverything() {
		this.incidentGitHubContextRepository.deleteAll();
		this.incidentRepository.deleteAll();
	}

	/** 기본 설정으로 뜬 Application에는 GitHub Client가 아예 없다. */
	@Test
	void startsWithoutAnyGitHubClient() {
		assertThat(this.applicationContext.getBeansOfType(IncidentGitHubContextClient.class)).isEmpty();
	}

	/** 꺼져 있는 것과 GitHub이 아픈 것을 같은 code로 답한다. Client가 둘을 두고 할 수 있는 일이 같다. */
	@Test
	void refusesToCollectAndSaysNothingAboutTheServerConfiguration() throws Exception {
		Incident incident = save(open());

		JsonNode body = this.objectMapper
			.readTree(this.mockMvc.perform(post("/api/incidents/{incidentId}/github-context", incident.getId()))
				.andExpect(status().isServiceUnavailable())
				.andReturn()
				.getResponse()
				.getContentAsString());

		assertThat(body.get("code").stringValue()).isEqualTo("GITHUB_CONTEXT_UNAVAILABLE");
		assertThat(this.objectMapper.writeValueAsString(body)).doesNotContain("disabled", "token", "enabled");
		assertThat(this.incidentGitHubContextRepository.count()).isZero();
	}

	/** 없는 Incident는 기능 상태보다 먼저 답한다. 잘못된 요청은 설정과 무관하게 잘못된 요청이다. */
	@Test
	void stillReportsAMissingIncidentBeforeTheFeatureState() throws Exception {
		JsonNode body = this.objectMapper
			.readTree(this.mockMvc.perform(post("/api/incidents/{incidentId}/github-context", 999_999L))
				.andExpect(status().isNotFound())
				.andReturn()
				.getResponse()
				.getContentAsString());

		assertThat(body.get("code").stringValue()).isEqualTo("INCIDENT_NOT_FOUND");
	}

	/**
	 * 이미 저장된 맥락은 기능이 꺼져 있어도 읽을 수 있다.
	 * <p>
	 * 설정이 정하는 것은 새로 모을 수 있는지이지, 이미 모아 둔 기록을 보여 줄 수 있는지가 아니다. token을
	 * 내린 뒤 지난 맥락을 읽지 못하게 되면 그 기록은 DB에만 남고, 그것을 근거로 만든 분석도 무엇을 봤는지
	 * 설명할 수 없게 된다.
	 */
	@Test
	void stillServesAContextThatWasStoredEarlier() throws Exception {
		Incident incident = save(open());
		this.incidentGitHubContextRepository.save(IncidentGitHubContext.of(incident.getId(), storedContext(),
				OPENED_AT));

		JsonNode body = this.objectMapper
			.readTree(this.mockMvc.perform(get("/api/incidents/{incidentId}/github-context", incident.getId()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString());

		assertThat(body.get("repository").stringValue()).isEqualTo("pulseops-test/pulseops");
		assertThat(body.get("files").get(0).get("path").stringValue()).isEqualTo(CONTROLLER_PATH);
	}

	/** 기능이 꺼져 있어도 Incident를 읽고 확인 처리하는 일은 그대로 된다. */
	@Test
	void leavesTheRestOfTheApplicationWorking() throws Exception {
		Incident incident = save(open());

		this.mockMvc.perform(get("/api/incidents/{incidentId}", incident.getId())).andExpect(status().isOk());
		this.mockMvc.perform(post("/api/incidents/{incidentId}/acknowledge", incident.getId()))
			.andExpect(status().isOk());
		this.mockMvc.perform(get("/api/incidents/active")).andExpect(status().isOk());
	}

	private static GitHubCodeContext storedContext() {
		return new GitHubCodeContext("pulseops-test/pulseops", "main", "demo-shop/backend/src/main/java",
				List.of(new GitHubSourceFile(CONTROLLER_PATH, "1111111111111111111111111111111111111111",
						"class OrderController {}", false)),
				List.of());
	}

	private Incident save(Incident incident) {
		return this.incidentRepository.saveAndFlush(incident);
	}

	private static Incident open() {
		return Incident.open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL,
				new MetricSnapshot("demo-shop", "GET", "/api/orders/{orderId}", WINDOW_START, OPENED_AT, 170, 140,
						82.35, 180L),
				OPENED_AT);
	}

}

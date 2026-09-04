package com.pulseops.analysis;

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
 * AI 기능이 꺼진 기본 설정에서 Application이 어떻게 동작하는지 고정한다.
 * <p>
 * 이것이 이 프로젝트의 기본 상태다. 여기에 가짜 Client를 세우지 않는 이유가 그것이며, 그래서 이
 * 테스트는 credential 없이 Application이 정상적으로 뜨고 나머지 기능이 그대로 동작한다는 것을 함께
 * 확인한다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentAiAnalysisDisabledIntegrationTest {

	private static final String SERVICE = "demo-shop";

	private static final Instant WINDOW_START = Instant.parse("2026-09-01T05:59:00Z");

	private static final Instant OPENED_AT = Instant.parse("2026-09-01T06:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private IncidentAiAnalysisRepository incidentAiAnalysisRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@AfterEach
	void removeEverything() {
		this.incidentAiAnalysisRepository.deleteAll();
		this.incidentRepository.deleteAll();
	}

	/**
	 * 기능이 꺼져 있으면 Client 자체가 없다. 껍데기를 만들어 두고 안에서 거절하면, 기능이 꺼져 있다는
	 * 사실이 Application의 구조가 아니라 어느 method 안의 분기가 된다.
	 */
	@Test
	void createsNoProviderClientWhenTheFeatureIsOff() {
		assertThat(this.applicationContext.getBeansOfType(IncidentAnalysisClient.class)).isEmpty();
	}

	/** credential이 없어도 Application은 정상적으로 뜬다. 나머지 기능은 이 기능과 무관하다. */
	@Test
	void keepsTheRestOfTheApplicationWorking() throws Exception {
		save(open());

		this.mockMvc.perform(get("/api/incidents/active")).andExpect(status().isOk());
	}

	@Test
	void refusesToCreateAnAnalysis() throws Exception {
		Incident incident = save(open());

		JsonNode body = this.objectMapper
			.readTree(this.mockMvc.perform(post("/api/incidents/{incidentId}/analysis", incident.getId()))
				.andExpect(status().isServiceUnavailable())
				.andReturn()
				.getResponse()
				.getContentAsString());

		assertThat(body.get("code").stringValue()).isEqualTo("AI_ANALYSIS_UNAVAILABLE");
		assertThat(this.incidentAiAnalysisRepository.count()).isZero();
	}

	/**
	 * 이미 저장된 분석은 기능이 꺼져 있어도 읽을 수 있다.
	 * <p>
	 * 설정이 정하는 것은 새 요청을 보낼 수 있는지이지, 이미 만들어진 기록을 보여 줄 수 있는지가 아니다.
	 * credential을 내린 뒤 지난 분석을 읽지 못하게 되면 그 기록은 DB에만 남고 아무도 볼 수 없는 값이 된다.
	 */
	@Test
	void stillServesAnAnalysisThatWasStoredEarlier() throws Exception {
		Incident incident = save(open());
		this.incidentAiAnalysisRepository.save(IncidentAiAnalysis.incidentOnly(incident.getId(), storedResult(), OPENED_AT));

		JsonNode body = this.objectMapper
			.readTree(this.mockMvc.perform(get("/api/incidents/{incidentId}/analysis", incident.getId()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString());

		assertThat(body.get("summary").stringValue()).isEqualTo("예전에 저장된 요약이다.");
		assertThat(body.get("model").stringValue()).isEqualTo("claude-test-model");
	}

	/** Incident가 없는 것은 기능 상태보다 먼저 답해야 할 사실이다. */
	@Test
	void stillReportsAMissingIncident() throws Exception {
		this.mockMvc.perform(post("/api/incidents/{incidentId}/analysis", 999_999L))
			.andExpect(status().isNotFound());
	}

	private static IncidentAnalysisResult storedResult() {
		return new IncidentAnalysisResult("예전에 저장된 요약이다.", "의존 서비스의 실패가 의심된다.", AnalysisConfidence.LOW,
				List.of("근거"), List.of("조치"), List.of("한계"), "claude-test-model", IncidentAnalysisPrompt.VERSION,
				"{\"service\":\"demo-shop\"}");
	}

	private Incident save(Incident incident) {
		return this.incidentRepository.save(incident);
	}

	private static Incident open() {
		return Incident.open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL, new MetricSnapshot(SERVICE, "POST",
				"/api/orders", WINDOW_START, OPENED_AT, 170, 140, 82.35, 180L), OPENED_AT);
	}

}

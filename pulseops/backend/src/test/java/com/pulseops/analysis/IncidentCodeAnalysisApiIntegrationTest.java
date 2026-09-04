package com.pulseops.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.github.FakeIncidentGitHubContextClient;
import com.pulseops.github.FakeIncidentGitHubContextClientConfiguration;
import com.pulseops.github.IncidentGitHubContextRepository;
import com.pulseops.incident.Incident;
import com.pulseops.incident.IncidentRepository;
import com.pulseops.incident.IncidentSeverity;
import com.pulseops.incident.IncidentType;
import com.pulseops.metric.MetricSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 코드 맥락까지 본 분석이 기본 분석과 어떻게 공존하는지를 실제 MySQL 위에서 확인한다.
 * <p>
 * 여기서 확인할 것은 두 분석이 서로를 건드리지 않는다는 것이다. 뒤에 만든 것이 앞의 것을 덮거나, 한쪽을
 * 물었는데 다른 쪽이 돌아오면 두 분석의 근거가 어떻게 달랐는지 사람이 비교할 수 없게 되고, 그 비교가 이
 * 기능에서 실제로 배울 수 있는 거의 전부이다. 그 규칙을 마지막으로 보장하는 것은 DB의 UNIQUE이므로
 * 저장소를 흉내 내지 않는다.
 * <p>
 * 진짜 Claude도 진짜 GitHub도 부르지 않는다. 언제 부르고 언제 부르지 않는지는 전부 우리가 정한 규칙이다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import({ MySqlTestcontainersConfiguration.class, FakeIncidentAnalysisClientConfiguration.class,
		FakeIncidentGitHubContextClientConfiguration.class })
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentCodeAnalysisApiIntegrationTest {

	private static final Instant WINDOW_START = Instant.parse("2026-09-01T05:59:00Z");

	private static final Instant OPENED_AT = Instant.parse("2026-09-01T06:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private IncidentAiAnalysisRepository incidentAiAnalysisRepository;

	@Autowired
	private IncidentGitHubContextRepository incidentGitHubContextRepository;

	@Autowired
	private FakeIncidentAnalysisClient analysisClient;

	@Autowired
	private FakeIncidentGitHubContextClient gitHubClient;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void resetClients() {
		this.analysisClient.reset();
		this.gitHubClient.reset();
	}

	@AfterEach
	void removeEverything() {
		// 분석이 맥락을, 맥락이 Incident를 참조하므로 순서를 지킨다
		this.incidentAiAnalysisRepository.deleteAll();
		this.incidentGitHubContextRepository.deleteAll();
		this.incidentRepository.deleteAll();
	}

	/**
	 * 맥락이 없으면 Claude를 부르지 않는다. 여기서 대신 수집해 주면 사용자는 버튼 하나로 GitHub 한도와
	 * AI 요금을 동시에 쓰게 되고, 어느 쪽이 실패했는지도 한 번의 오류로 뭉뚱그려진다.
	 */
	@Test
	void refusesToAnalyzeCodeBeforeAnyContextWasCollected() throws Exception {
		Incident incident = save(open());

		JsonNode body = createCodeAnalysis(incident.getId(), status().isNotFound());

		assertThat(body.get("code").stringValue()).isEqualTo("GITHUB_CONTEXT_NOT_FOUND");
		assertThat(this.analysisClient.callCount()).isZero();
		assertThat(this.gitHubClient.callCount()).isZero();
	}

	/**
	 * 관련 source가 없는 맥락으로도 만들지 않는다. 그렇게 만든 분석은 이름만 코드 기반일 뿐 기본 분석과
	 * 같은 근거로 만든 두 번째 분석이며, 두 분석의 차이를 사람에게 설명할 수 없다.
	 */
	@Test
	void refusesToAnalyzeCodeWhenTheContextFoundNothing() throws Exception {
		Incident incident = save(open());
		this.gitHubClient.findNothing();
		collectContext(incident.getId());

		JsonNode body = createCodeAnalysis(incident.getId(), status().isConflict());

		assertThat(body.get("code").stringValue()).isEqualTo("GITHUB_CONTEXT_EMPTY");
		assertThat(this.analysisClient.callCount()).isZero();
	}

	@Test
	void createsACodeAwareAnalysisFromTheStoredContext() throws Exception {
		Incident incident = save(open());
		collectContext(incident.getId());

		JsonNode body = createCodeAnalysis(incident.getId(), status().isOk());

		assertThat(body.get("incidentId").longValue()).isEqualTo(incident.getId());
		assertThat(this.analysisClient.callCount()).isEqualTo(1);
		assertThat(this.analysisClient.receivedCodeContexts()).hasSize(1);
		assertThat(this.analysisClient.receivedCodeContexts().get(0).files()).hasSize(1);
	}

	/**
	 * 분석을 만드는 동안 GitHub을 한 번도 부르지 않는다. 근거는 이미 저장된 맥락이며, 여기서 다시 찾아
	 * 나서면 같은 Incident의 두 분석이 서로 다른 코드를 보고 만들어질 수 있다.
	 */
	@Test
	void neverCallsGitHubWhileBuildingTheAnalysis() throws Exception {
		Incident incident = save(open());
		collectContext(incident.getId());
		int callsAfterCollecting = this.gitHubClient.callCount();

		createCodeAnalysis(incident.getId(), status().isOk());
		createCodeAnalysis(incident.getId(), status().isOk());

		assertThat(this.gitHubClient.callCount()).isEqualTo(callsAfterCollecting);
	}

	/** 다시 요청해도 Claude를 부르지 않는다. 화면을 새로 고치는 것만으로 요금이 붙으면 안 된다. */
	@Test
	void reusesAnExistingCodeAwareAnalysis() throws Exception {
		Incident incident = save(open());
		collectContext(incident.getId());

		JsonNode first = createCodeAnalysis(incident.getId(), status().isOk());
		JsonNode second = createCodeAnalysis(incident.getId(), status().isOk());

		assertThat(this.analysisClient.callCount()).isEqualTo(1);
		assertThat(second).isEqualTo(first);
	}

	/**
	 * 같은 Incident에 두 분석이 나란히 남는다.
	 * <p>
	 * 이것이 이 Phase의 핵심 계약이다. 근거가 다른 두 분석이 함께 있어야 사람이 코드 맥락을 더한 것이
	 * 실제로 무엇을 바꿨는지 볼 수 있다. 한쪽이 다른 쪽을 덮으면 그 비교가 불가능해진다.
	 */
	@Test
	void keepsTheBaseAnalysisAndTheCodeAwareAnalysisSideBySide() throws Exception {
		Incident incident = save(open());
		createAnalysis(incident.getId(), status().isOk());
		collectContext(incident.getId());

		createCodeAnalysis(incident.getId(), status().isOk());

		assertThat(this.incidentAiAnalysisRepository.count()).isEqualTo(2);
		assertThat(this.incidentAiAnalysisRepository
			.findByIncidentIdAndAnalysisType(incident.getId(), IncidentAnalysisType.INCIDENT_ONLY)).isPresent();
		assertThat(this.incidentAiAnalysisRepository
			.findByIncidentIdAndAnalysisType(incident.getId(), IncidentAnalysisType.GITHUB_ENRICHED)).isPresent();
	}

	/** 코드 기반 분석이 생겨도 기본 분석은 한 글자도 바뀌지 않는다. */
	@Test
	void leavesTheStoredBaseAnalysisExactlyAsItWas() throws Exception {
		Incident incident = save(open());
		JsonNode base = createAnalysis(incident.getId(), status().isOk());
		collectContext(incident.getId());

		createCodeAnalysis(incident.getId(), status().isOk());

		assertThat(getAnalysis(incident.getId(), status().isOk())).isEqualTo(base);
	}

	/** 어느 쪽을 물었는지에 따라 정확히 그 분석이 돌아온다. 뒤바뀌면 근거와 결론이 어긋난다. */
	@Test
	void neverSwapsTheTwoAnalysesWhenReadingThem() throws Exception {
		Incident incident = save(open());
		createAnalysis(incident.getId(), status().isOk());
		collectContext(incident.getId());
		createCodeAnalysis(incident.getId(), status().isOk());

		IncidentAiAnalysis base = this.incidentAiAnalysisRepository
			.findByIncidentIdAndAnalysisType(incident.getId(), IncidentAnalysisType.INCIDENT_ONLY)
			.orElseThrow();
		IncidentAiAnalysis enriched = this.incidentAiAnalysisRepository
			.findByIncidentIdAndAnalysisType(incident.getId(), IncidentAnalysisType.GITHUB_ENRICHED)
			.orElseThrow();

		assertThat(getAnalysis(incident.getId(), status().isOk()).get("generatedAt").stringValue())
			.isEqualTo(base.getGeneratedAt().toString());
		assertThat(getCodeAnalysis(incident.getId(), status().isOk()).get("generatedAt").stringValue())
			.isEqualTo(enriched.getGeneratedAt().toString());
		assertThat(base.getId()).isNotEqualTo(enriched.getId());
	}

	/**
	 * 어떤 맥락을 보고 만들어졌는지가 남는다. 분석이 인용하는 경로와 SHA는 전부 그 맥락 안에 있으므로,
	 * 가리키지 않으면 나중에 인용이 맞았는지 확인할 방법이 없다. 기본 분석에는 맥락이 없다.
	 */
	@Test
	void recordsWhichContextAndWhichPromptProducedEachAnalysis() throws Exception {
		Incident incident = save(open());
		createAnalysis(incident.getId(), status().isOk());
		collectContext(incident.getId());
		createCodeAnalysis(incident.getId(), status().isOk());

		IncidentAiAnalysis base = this.incidentAiAnalysisRepository
			.findByIncidentIdAndAnalysisType(incident.getId(), IncidentAnalysisType.INCIDENT_ONLY)
			.orElseThrow();
		IncidentAiAnalysis enriched = this.incidentAiAnalysisRepository
			.findByIncidentIdAndAnalysisType(incident.getId(), IncidentAnalysisType.GITHUB_ENRICHED)
			.orElseThrow();

		assertThat(base.getGitHubContextId()).isNull();
		assertThat(enriched.getGitHubContextId())
			.isEqualTo(this.incidentGitHubContextRepository.findByIncidentId(incident.getId()).orElseThrow().getId());
		assertThat(base.getAnalysisType()).isEqualTo(IncidentAnalysisType.INCIDENT_ONLY);
		assertThat(enriched.getAnalysisType()).isEqualTo(IncidentAnalysisType.GITHUB_ENRICHED);
	}

	/**
	 * 저장되는 snapshot은 Incident 사실이며 소스 조각이 아니다. 맥락은 이미 별도의 기록으로 저장되어
	 * 있고, 분석 행에 한 번 더 복사해 두면 언젠가 두 값이 서로 다른 것을 가리킨다.
	 */
	@Test
	void doesNotCopyTheSourceContextIntoTheAnalysisRow() throws Exception {
		Incident incident = save(open());
		collectContext(incident.getId());

		createCodeAnalysis(incident.getId(), status().isOk());

		IncidentAiAnalysis enriched = this.incidentAiAnalysisRepository
			.findByIncidentIdAndAnalysisType(incident.getId(), IncidentAnalysisType.GITHUB_ENRICHED)
			.orElseThrow();
		assertThat(enriched.getInputSnapshot()).doesNotContain("OrderController");
		assertThat(this.objectMapper.readTree(enriched.getInputSnapshot()).get("endpoint").stringValue())
			.isEqualTo("/api/orders/{orderId}");
	}

	@Test
	void reportsThatAnIncidentHasNoCodeAwareAnalysisYet() throws Exception {
		Incident incident = save(open());
		createAnalysis(incident.getId(), status().isOk());

		JsonNode body = getCodeAnalysis(incident.getId(), status().isNotFound());

		assertThat(body.get("code").stringValue()).isEqualTo("INCIDENT_ANALYSIS_NOT_FOUND");
	}

	/**
	 * Claude가 실패해도 이미 모아 둔 맥락은 그대로 남는다. 다음 시도에서 GitHub을 다시 부르지 않아도
	 * 되는 것이 그 덕분이다.
	 */
	@Test
	void keepsTheCollectedContextWhenTheAnalysisFails() throws Exception {
		Incident incident = save(open());
		collectContext(incident.getId());
		this.analysisClient.failWith(new IncidentAnalysisUnavailableException("provider_http_500", null));

		JsonNode body = createCodeAnalysis(incident.getId(), status().isServiceUnavailable());

		assertThat(body.get("code").stringValue()).isEqualTo("AI_ANALYSIS_UNAVAILABLE");
		assertThat(this.incidentAiAnalysisRepository.count()).isZero();
		assertThat(this.incidentGitHubContextRepository.count()).isEqualTo(1);
	}

	@Test
	void succeedsOnALaterAttemptWithoutCollectingTheContextAgain() throws Exception {
		Incident incident = save(open());
		collectContext(incident.getId());
		this.analysisClient.failWith(new IncidentAnalysisUnavailableException("provider_http_500", null));
		createCodeAnalysis(incident.getId(), status().isServiceUnavailable());
		int gitHubCalls = this.gitHubClient.callCount();

		this.analysisClient.reset();
		createCodeAnalysis(incident.getId(), status().isOk());

		assertThat(this.gitHubClient.callCount()).isEqualTo(gitHubCalls);
		assertThat(this.incidentAiAnalysisRepository.count()).isEqualTo(1);
	}

	/** Claude 응답을 기다리는 동안 Transaction을 쥐고 있지 않다. Phase 9와 같은 불변이다. */
	@Test
	void callsClaudeWithoutHoldingATransaction() throws Exception {
		Incident incident = save(open());
		collectContext(incident.getId());

		createCodeAnalysis(incident.getId(), status().isOk());

		assertThat(this.analysisClient.calledInsideTransaction()).isFalse();
	}

	/** 코드 기반 분석을 만들어도 Incident는 한 글자도 바뀌지 않는다. AI는 탐지기가 아니다. */
	@Test
	void changesNothingAboutTheIncidentItself() throws Exception {
		Incident incident = save(open());
		collectContext(incident.getId());
		Instant updatedAtBefore = incident.getUpdatedAt();

		createCodeAnalysis(incident.getId(), status().isOk());

		Incident reloaded = this.incidentRepository.findById(incident.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(incident.getStatus());
		assertThat(reloaded.getSeverity()).isEqualTo(incident.getSeverity());
		assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAtBefore);
	}

	/**
	 * 맥락을 모아 두기만 하고 분석을 요청하지 않으면 Claude는 한 번도 불리지 않는다. 두 단계를 사람이
	 * 따로 통제한다는 설계가 실제로 지켜지는지는 이 값으로만 확인된다.
	 */
	@Test
	void spendsNothingOnClaudeUntilSomeoneAsksForTheAnalysis() throws Exception {
		Incident incident = save(open());

		collectContext(incident.getId());

		assertThat(this.analysisClient.callCount()).isZero();
		assertThat(this.incidentAiAnalysisRepository.count()).isZero();
	}

	private void collectContext(Long incidentId) throws Exception {
		this.mockMvc.perform(post("/api/incidents/{incidentId}/github-context", incidentId))
			.andExpect(status().isOk());
	}

	private JsonNode createAnalysis(Long incidentId, ResultMatcher expectedStatus) throws Exception {
		return perform(post("/api/incidents/{incidentId}/analysis", incidentId), expectedStatus);
	}

	private JsonNode getAnalysis(Long incidentId, ResultMatcher expectedStatus) throws Exception {
		return perform(get("/api/incidents/{incidentId}/analysis", incidentId), expectedStatus);
	}

	private JsonNode createCodeAnalysis(Long incidentId, ResultMatcher expectedStatus) throws Exception {
		return perform(post("/api/incidents/{incidentId}/analysis/github", incidentId), expectedStatus);
	}

	private JsonNode getCodeAnalysis(Long incidentId, ResultMatcher expectedStatus) throws Exception {
		return perform(get("/api/incidents/{incidentId}/analysis/github", incidentId), expectedStatus);
	}

	private JsonNode perform(RequestBuilder request, ResultMatcher expectedStatus)
			throws Exception {
		return this.objectMapper.readTree(
				this.mockMvc.perform(request).andExpect(expectedStatus).andReturn().getResponse().getContentAsString());
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

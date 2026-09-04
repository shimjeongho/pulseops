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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 분석을 만들고 읽는 흐름이 실제 MySQL 위에서 어떻게 동작하는지 끝에서 끝까지 확인한다.
 * <p>
 * 진짜 Claude는 부르지 않는다. 여기서 확인할 것은 모델이 무엇을 말하는지가 아니라, 언제 부르고 언제
 * 부르지 않으며 무엇을 남기고 무엇을 남기지 않는지이다. 그 규칙은 전부 우리가 정한 것이다.
 * <p>
 * 저장소를 흉내 내지 않는다. Incident당 분석이 하나뿐인 것을 마지막으로 보장하는 것은 DB의 UNIQUE이고,
 * 흉내 낸 저장소는 그 제약을 갖고 있지 않아 가장 중요한 것을 확인하지 못한다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import({ MySqlTestcontainersConfiguration.class, FakeIncidentAnalysisClientConfiguration.class })
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentAiAnalysisApiIntegrationTest {

	private static final List<String> RESPONSE_FIELDS = List.of("incidentId", "summary", "primaryHypothesis",
			"confidence", "evidence", "recommendedActions", "limitations", "model", "generatedAt");

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "POST";

	private static final String ENDPOINT = "/api/orders";

	private static final Instant WINDOW_START = Instant.parse("2026-09-01T05:59:00Z");

	private static final Instant OPENED_AT = Instant.parse("2026-09-01T06:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private IncidentAiAnalysisRepository incidentAiAnalysisRepository;

	@Autowired
	private FakeIncidentAnalysisClient analysisClient;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void resetClient() {
		this.analysisClient.reset();
	}

	@AfterEach
	void removeEverything() {
		// 분석이 Incident를 참조하므로 순서를 지킨다
		this.incidentAiAnalysisRepository.deleteAll();
		this.incidentRepository.deleteAll();
	}

	@Test
	void createsAnAnalysisForAnExistingIncident() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));

		JsonNode body = createAnalysis(incident.getId(), status().isOk());

		assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(RESPONSE_FIELDS);
		assertThat(body.get("incidentId").longValue()).isEqualTo(incident.getId());
		assertThat(body.get("summary").stringValue()).isEqualTo("POST /api/orders의 오류가 늘었다.");
		assertThat(body.get("confidence").stringValue()).isEqualTo("MEDIUM");
		assertThat(body.get("model").stringValue()).isEqualTo("claude-test-model");
		assertThat(body.get("limitations").size()).isEqualTo(1);
	}

	/** 내부 감사용 값은 API로 나가지 않는다. 화면이 할 일이 없고, snapshot은 지금 값과 헷갈린다. */
	@Test
	void keepsAuditMetadataOutOfTheResponse() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));

		JsonNode body = createAnalysis(incident.getId(), status().isOk());

		assertThat(body.propertyNames()).doesNotContain("promptVersion", "inputSnapshot", "id");
	}

	/** Incident가 없으면 기능이 켜져 있든 꺼져 있든 잘못된 요청이다. */
	@Test
	void refusesToAnalyzeAnIncidentThatDoesNotExist() throws Exception {
		JsonNode body = createAnalysis(999_999L, status().isNotFound());

		assertThat(body.get("code").stringValue()).isEqualTo("INCIDENT_NOT_FOUND");
		assertThat(this.analysisClient.callCount()).isZero();
	}

	@Test
	void rejectsAnIncidentIdThatIsNotANumber() throws Exception {
		this.mockMvc.perform(get("/api/incidents/not-a-number/analysis")).andExpect(status().isBadRequest());
	}

	/**
	 * 같은 Incident에 다시 요청해도 Claude를 부르지 않는다. 화면을 새로 고치거나 버튼을 다시 누르는 것
	 * 만으로 요금이 붙으면 안 된다.
	 */
	@Test
	void reusesAnExistingAnalysisWithoutCallingTheProviderAgain() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));

		JsonNode first = createAnalysis(incident.getId(), status().isOk());
		JsonNode second = createAnalysis(incident.getId(), status().isOk());

		assertThat(this.analysisClient.callCount()).isEqualTo(1);
		assertThat(second).isEqualTo(first);
		assertThat(this.incidentAiAnalysisRepository.count()).isEqualTo(1);
	}

	/** 저장된 분석은 다시 읽어도 같아야 한다. 영속성 Context가 아니라 DB에 남아 있는지를 본다. */
	@Test
	void servesTheStoredAnalysisOnALaterRequest() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));
		JsonNode created = createAnalysis(incident.getId(), status().isOk());

		JsonNode read = getAnalysis(incident.getId(), status().isOk());

		assertThat(read).isEqualTo(created);
		assertThat(this.incidentAiAnalysisRepository.findByIncidentId(incident.getId())).isPresent();
	}

	@Test
	void reportsThatAnIncidentHasNoAnalysisYet() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));

		JsonNode body = getAnalysis(incident.getId(), status().isNotFound());

		assertThat(body.get("code").stringValue()).isEqualTo("INCIDENT_ANALYSIS_NOT_FOUND");
	}

	/** 없는 Incident와 분석이 없는 Incident를 나눈다. 화면이 분석 버튼을 그릴지 정하는 근거이다. */
	@Test
	void separatesAMissingIncidentFromAMissingAnalysis() throws Exception {
		JsonNode body = getAnalysis(999_999L, status().isNotFound());

		assertThat(body.get("code").stringValue()).isEqualTo("INCIDENT_NOT_FOUND");
	}

	/** 지난 장애를 사후에 들여다보는 것이 이 기능의 주된 쓰임이다. 상태가 사용 권한이 아니다. */
	@Test
	void analyzesAnAlreadyResolvedIncident() throws Exception {
		Incident incident = open(IncidentType.LATENCY, IncidentSeverity.WARNING);
		incident.resolve(Instant.parse("2026-09-01T06:10:00Z"));
		Incident resolved = save(incident);

		createAnalysis(resolved.getId(), status().isOk());

		assertThat(this.analysisClient.callCount()).isEqualTo(1);
	}

	/** 호출이 실패하면 아무것도 남기지 않는다. 남기면 그 Incident는 영원히 분석할 수 없게 된다. */
	@Test
	void storesNothingWhenTheProviderFails() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));
		this.analysisClient.failWith(new IncidentAnalysisUnavailableException("provider_unreachable", null));

		JsonNode body = createAnalysis(incident.getId(), status().isServiceUnavailable());

		assertThat(body.get("code").stringValue()).isEqualTo("AI_ANALYSIS_UNAVAILABLE");
		assertThat(this.incidentAiAnalysisRepository.count()).isZero();
	}

	/** 한 번 실패했다고 그 Incident가 영영 분석 불가가 되면 안 된다. */
	@Test
	void allowsAnotherAttemptAfterAFailure() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));
		this.analysisClient.failWith(new IncidentAnalysisUnavailableException("provider_unreachable", null));
		createAnalysis(incident.getId(), status().isServiceUnavailable());

		this.analysisClient.reset();
		createAnalysis(incident.getId(), status().isOk());

		assertThat(this.incidentAiAnalysisRepository.count()).isEqualTo(1);
	}

	/** provider가 돌려준 문장이 Client 응답으로 새어 나가면 안 된다. */
	@Test
	void keepsProviderErrorDetailOutOfTheResponse() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));
		this.analysisClient.failWith(new IncidentAnalysisUnavailableException("provider_http_401",
				new IllegalStateException("invalid x-api-key sk-ant-dummy")));

		JsonNode body = createAnalysis(incident.getId(), status().isServiceUnavailable());

		assertThat(body.get("message").stringValue()).isEqualTo("AI 분석 서비스를 현재 사용할 수 없습니다.");
		assertThat(body.toString()).doesNotContain("sk-ant", "x-api-key", "provider_http_401");
	}

	/**
	 * 외부 호출을 기다리는 동안 DB Transaction을 쥐고 있지 않는다. 쥐고 있으면 분석 요청 몇 개가 연결
	 * 풀을 비워 Incident 조회와 확인 처리가 함께 막힌다.
	 */
	@Test
	void callsTheProviderOutsideAnyTransaction() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));

		createAnalysis(incident.getId(), status().isOk());

		assertThat(this.analysisClient.calledInsideTransaction()).isFalse();
	}

	/** 분석이 Incident를 바꾸지 않는다. AI는 탐지기가 아니며 판정에 참여하지 않는다. */
	@Test
	void leavesTheIncidentUntouched() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));
		Instant updatedBefore = incident.getUpdatedAt();

		createAnalysis(incident.getId(), status().isOk());

		Incident reloaded = this.incidentRepository.findById(incident.getId()).orElseThrow();
		assertThat(reloaded.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
		assertThat(reloaded.getStatus()).isEqualTo(incident.getStatus());
		assertThat(reloaded.getAcknowledgedAt()).isNull();
		assertThat(reloaded.getResolvedAt()).isNull();
		assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedBefore);
	}

	/** 보내지 않기로 한 값은 실제로도 넘어가지 않는다. */
	@Test
	void sendsOnlyTheIncidentFactsWeChose() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));

		createAnalysis(incident.getId(), status().isOk());

		IncidentAnalysisFacts sent = this.analysisClient.receivedFacts().get(0);
		assertThat(sent.service()).isEqualTo(SERVICE);
		assertThat(sent.endpoint()).isEqualTo(ENDPOINT);
		assertThat(sent.triggerErrorCount()).isEqualTo(140);
		assertThat(this.objectMapper.readTree(IncidentAnalysisPrompt.factsJson(sent)).propertyNames())
			.doesNotContain("id", "incidentId");
	}

	/**
	 * 분석 뒤 Incident가 확인되어도 저장된 입력은 그때 값을 유지한다.
	 * <p>
	 * 지금의 Incident 행을 근거처럼 읽으면, 분석이 보지도 않은 값을 보고 만든 것처럼 보인다.
	 */
	@Test
	void keepsTheInputSnapshotAsItWasAtAnalysisTime() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE, IncidentSeverity.WARNING));
		createAnalysis(incident.getId(), status().isOk());

		this.mockMvc.perform(post("/api/incidents/{incidentId}/acknowledge", incident.getId()))
			.andExpect(status().isOk());

		String snapshot = this.incidentAiAnalysisRepository.findByIncidentId(incident.getId())
			.orElseThrow()
			.getInputSnapshot();
		assertThat(this.objectMapper.readTree(snapshot).get("status").stringValue()).isEqualTo("OPEN");
		assertThat(this.incidentRepository.findById(incident.getId()).orElseThrow().getStatus().name())
			.isEqualTo("ACKNOWLEDGED");
	}

	/** 어떤 model과 어떤 지시로 만들어진 분석인지 남는다. 남지 않으면 나중에 무엇도 설명할 수 없다. */
	@Test
	void recordsTheModelAndThePromptVersionThatProducedIt() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));

		createAnalysis(incident.getId(), status().isOk());

		IncidentAiAnalysis stored = this.incidentAiAnalysisRepository.findByIncidentId(incident.getId()).orElseThrow();
		assertThat(stored.getModel()).isEqualTo("claude-test-model");
		assertThat(stored.getPromptVersion()).isEqualTo(IncidentAnalysisPrompt.VERSION);
	}

	/**
	 * 같은 Incident에 두 요청이 나란히 들어와도 남는 행은 하나다.
	 * <p>
	 * 두 요청을 Client 안에서 만나게 해 둘 다 저장된 분석이 없다고 본 상태를 만든다. 그 상태가 바로
	 * Application의 존재 확인만으로는 막을 수 없는 경우이며, 마지막으로 막는 것은 DB의 UNIQUE이다.
	 * <p>
	 * 그때 provider 호출이 두 번 나가는 것은 알고 남겨 둔 부채다. 여기서도 그 사실을 그대로 확인한다.
	 * 값이 바뀌면 테스트가 실패해, 부채가 갚아졌거나 더 나빠졌다는 것이 드러난다.
	 */
	@Test
	void keepsExactlyOneAnalysisWhenTwoRequestsRace() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));
		this.analysisClient.rendezvousOf(2);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Callable<JsonNode> request = () -> createAnalysis(incident.getId(), status().isOk());
			List<Future<JsonNode>> results = executor.invokeAll(List.of(request, request));
			executor.shutdown();
			assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

			assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(this.incidentAiAnalysisRepository.count()).isEqualTo(1);
		assertThat(this.analysisClient.callCount()).isEqualTo(2);
	}

	private JsonNode createAnalysis(Long incidentId, ResultMatcher expected) throws Exception {
		return this.objectMapper.readTree(this.mockMvc.perform(post("/api/incidents/{incidentId}/analysis", incidentId))
			.andExpect(expected)
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	private JsonNode getAnalysis(Long incidentId, ResultMatcher expected) throws Exception {
		return this.objectMapper.readTree(this.mockMvc.perform(get("/api/incidents/{incidentId}/analysis", incidentId))
			.andExpect(expected)
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	private Incident save(Incident incident) {
		return this.incidentRepository.save(incident);
	}

	private static Incident open(IncidentType type, IncidentSeverity severity) {
		return Incident.open(type, severity,
				new MetricSnapshot(SERVICE, METHOD, ENDPOINT, WINDOW_START, OPENED_AT, 170, 140, 82.35, 180L),
				OPENED_AT);
	}

}

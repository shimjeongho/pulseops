package com.pulseops.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.incident.DetectionStateRepository;
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
 * GitHub 코드 맥락을 모으고 읽는 흐름이 실제 MySQL 위에서 어떻게 동작하는지 끝에서 끝까지 확인한다.
 * <p>
 * 진짜 GitHub은 부르지 않는다. 여기서 확인할 것은 GitHub이 무엇을 갖고 있는지가 아니라, 언제 부르고
 * 언제 부르지 않으며 무엇을 남기고 무엇을 남기지 않는지이다. 그 규칙은 전부 우리가 정한 것이다.
 * <p>
 * 저장소를 흉내 내지 않는다. Incident당 맥락이 하나뿐인 것을 마지막으로 보장하는 것은 DB의 UNIQUE이고,
 * 흉내 낸 저장소는 그 제약을 갖고 있지 않아 가장 중요한 것을 확인하지 못한다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import({ MySqlTestcontainersConfiguration.class, FakeIncidentGitHubContextClientConfiguration.class })
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentGitHubContextApiIntegrationTest {

	private static final List<String> RESPONSE_FIELDS = List.of("incidentId", "repository", "ref", "sourceRoot",
			"files", "commits", "capturedAt");

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "GET";

	private static final String ENDPOINT = "/api/orders/{orderId}";

	private static final Instant WINDOW_START = Instant.parse("2026-09-01T05:59:00Z");

	private static final Instant OPENED_AT = Instant.parse("2026-09-01T06:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private DetectionStateRepository detectionStateRepository;

	@Autowired
	private IncidentGitHubContextRepository incidentGitHubContextRepository;

	@Autowired
	private FakeIncidentGitHubContextClient gitHubClient;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void resetClient() {
		this.gitHubClient.reset();
	}

	@AfterEach
	void removeEverything() {
		// 맥락이 Incident를 참조하므로 순서를 지킨다
		this.incidentGitHubContextRepository.deleteAll();
		this.incidentRepository.deleteAll();
	}

	@Test
	void collectsCodeContextForAnExistingIncident() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));

		JsonNode body = collect(incident.getId(), status().isOk());

		assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(RESPONSE_FIELDS);
		assertThat(body.get("incidentId").longValue()).isEqualTo(incident.getId());
		assertThat(body.get("repository").stringValue()).isEqualTo(FakeIncidentGitHubContextClient.REPOSITORY);
		assertThat(body.get("files").size()).isEqualTo(1);
		assertThat(body.get("files").get(0).get("path").stringValue()).contains("OrderController.java");
		assertThat(body.get("commits").size()).isEqualTo(1);
	}

	/** 무엇을 찾을지는 Incident가 정한다. service나 severity는 넘기지 않는다. */
	@Test
	void asksOnlyForTheEndpointAndTheTimeTheIncidentOpened() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));

		collect(incident.getId(), status().isOk());

		assertThat(this.gitHubClient.receivedQueries())
			.containsExactly(new IncidentGitHubContextQuery(ENDPOINT, OPENED_AT));
	}

	/**
	 * 소스 본문은 응답에 나가지 않는다. 화면이 코드 편집기가 아니고, 조각 하나가 수천 글자라 세 개만
	 * 실려도 상세 화면의 다른 내용이 전부 밀려난다.
	 */
	@Test
	void keepsTheSourceBodyOutOfTheResponse() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));

		JsonNode body = collect(incident.getId(), status().isOk());

		assertThat(body.get("files").get(0).propertyNames()).containsExactlyInAnyOrder("path", "revisionSha",
				"truncated");
		assertThat(this.objectMapper.writeValueAsString(body)).doesNotContain("@PostMapping");
	}

	/** commit에 사람을 가리키는 값이 없다. 이 기능은 누가 바꿨는지를 묻지 않는다. */
	@Test
	void keepsAuthorInformationOutOfTheResponse() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));

		JsonNode body = collect(incident.getId(), status().isOk());

		assertThat(body.get("commits").get(0).propertyNames()).containsExactlyInAnyOrder("sha", "message",
				"committedAt", "paths");
	}

	@Test
	void refusesToCollectForAnIncidentThatDoesNotExist() throws Exception {
		JsonNode body = collect(999_999L, status().isNotFound());

		assertThat(body.get("code").stringValue()).isEqualTo("INCIDENT_NOT_FOUND");
		assertThat(this.gitHubClient.callCount()).isZero();
	}

	@Test
	void rejectsAnIncidentIdThatIsNotANumber() throws Exception {
		this.mockMvc.perform(get("/api/incidents/not-a-number/github-context")).andExpect(status().isBadRequest());
	}

	/**
	 * 같은 Incident에 다시 요청해도 GitHub을 부르지 않는다. 화면을 새로 고치거나 버튼을 다시 누르는 것
	 * 만으로 분당 열 번뿐인 한도가 깎이면 안 된다.
	 */
	@Test
	void reusesAnExistingContextWithoutCallingGitHubAgain() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));

		JsonNode first = collect(incident.getId(), status().isOk());
		JsonNode second = collect(incident.getId(), status().isOk());

		assertThat(this.gitHubClient.callCount()).isEqualTo(1);
		assertThat(second).isEqualTo(first);
		assertThat(this.incidentGitHubContextRepository.count()).isEqualTo(1);
	}

	/** 저장된 맥락은 다시 읽어도 같아야 한다. 영속성 Context가 아니라 DB에 남아 있는지를 본다. */
	@Test
	void servesTheStoredContextOnALaterRequest() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));
		JsonNode created = collect(incident.getId(), status().isOk());

		JsonNode read = read(incident.getId(), status().isOk());

		assertThat(read).isEqualTo(created);
		assertThat(this.gitHubClient.callCount()).isEqualTo(1);
	}

	/** 어느 판을 읽었는지가 남는다. branch 이름만 남기면 같은 경로를 다시 읽었을 때 무엇도 확인할 수 없다. */
	@Test
	void recordsTheRevisionEachSnippetWasReadFrom() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));

		collect(incident.getId(), status().isOk());

		IncidentGitHubContext stored = this.incidentGitHubContextRepository.findByIncidentId(incident.getId())
			.orElseThrow();
		assertThat(stored.getRef()).isEqualTo(FakeIncidentGitHubContextClient.REF);
		assertThat(stored.getFiles().get(0).revisionSha()).isNotBlank();
		assertThat(stored.getSourceRoot()).isEqualTo(FakeIncidentGitHubContextClient.SOURCE_ROOT);
	}

	@Test
	void reportsThatAnIncidentHasNoContextYet() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));

		JsonNode body = read(incident.getId(), status().isNotFound());

		assertThat(body.get("code").stringValue()).isEqualTo("GITHUB_CONTEXT_NOT_FOUND");
	}

	@Test
	void separatesAMissingIncidentFromAMissingContext() throws Exception {
		JsonNode body = read(999_999L, status().isNotFound());

		assertThat(body.get("code").stringValue()).isEqualTo("INCIDENT_NOT_FOUND");
	}

	/**
	 * 관련 코드를 찾지 못한 것도 저장한다. 실패가 아니라 "찾아봤지만 없었다"는 결과이며, 그 사실이 남아야
	 * 다시 누를 때마다 같은 검색을 반복해 한도를 깎는 일이 없다.
	 */
	@Test
	void storesAContextThatFoundNothingSoTheSearchIsNotRepeated() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));
		this.gitHubClient.findNothing();

		JsonNode first = collect(incident.getId(), status().isOk());
		collect(incident.getId(), status().isOk());

		assertThat(first.get("files").size()).isZero();
		assertThat(this.gitHubClient.callCount()).isEqualTo(1);
		assertThat(this.incidentGitHubContextRepository.count()).isEqualTo(1);
	}

	/**
	 * 실패한 시도는 남기지 않는다. 남겨 두면 그 Incident는 다시 수집할 수 없거나, 실패 기록을 지우는 또
	 * 다른 경로가 필요해진다.
	 */
	@Test
	void storesNothingWhenGitHubFails() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));
		this.gitHubClient.failWith(new IncidentGitHubContextUnavailableException("provider_http_500", null));

		JsonNode body = collect(incident.getId(), status().isServiceUnavailable());

		assertThat(body.get("code").stringValue()).isEqualTo("GITHUB_CONTEXT_UNAVAILABLE");
		assertThat(this.incidentGitHubContextRepository.count()).isZero();
	}

	@Test
	void succeedsOnALaterAttemptAfterAFailure() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));
		this.gitHubClient.failWith(new IncidentGitHubContextUnavailableException("provider_rate_limited", null));
		collect(incident.getId(), status().isServiceUnavailable());

		this.gitHubClient.reset();
		collect(incident.getId(), status().isOk());

		assertThat(this.incidentGitHubContextRepository.count()).isEqualTo(1);
	}

	/** 실패 응답 어디에도 GitHub이 돌려준 문장이 없다. 거기에는 계정 사정이 담겨 올 수 있다. */
	@Test
	void neverLeaksTheProviderMessageOrACredential() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));
		this.gitHubClient
			.failWith(new IncidentGitHubContextUnavailableException("provider_http_401", new RuntimeException(
					"Bad credentials for token ghp_dummy_not_a_real_token")));

		JsonNode body = collect(incident.getId(), status().isServiceUnavailable());

		String response = this.objectMapper.writeValueAsString(body);
		assertThat(response).doesNotContain("ghp_", "Bad credentials", "provider_http_401");
	}

	/**
	 * 같은 Incident에 두 요청이 나란히 들어와도 남는 행은 하나다.
	 * <p>
	 * 두 요청을 GitHub 호출 지점에서 만나게 해 둘 다 "저장된 맥락이 없다"고 본 상태를 만든다. 그 상태를
	 * 만들지 않으면 먼저 끝난 요청의 결과를 뒤의 요청이 그대로 읽어 경쟁 자체가 일어나지 않는다.
	 * <p>
	 * 그때 GitHub 호출이 두 번 나가는 것은 남겨 둔 부채이다. 막으려면 아직 없는 행을 잠그거나 분산 잠금을
	 * 들여와야 하는데, 그것은 외부 API를 기다리는 동안 잠금을 쥐고 있겠다는 뜻이다. 정확성이 아니라 아주
	 * 가끔의 한도 낭비가 대가이다.
	 */
	@Test
	void keepsExactlyOneContextWhenTwoRequestsRaceEachOther() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));
		this.gitHubClient.rendezvousOf(2);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Callable<JsonNode> request = () -> collect(incident.getId(), status().isOk());
			Future<JsonNode> first = executor.submit(request);
			Future<JsonNode> second = executor.submit(request);

			assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
		}
		finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(this.incidentGitHubContextRepository.count()).isEqualTo(1);
	}

	/**
	 * GitHub 응답을 기다리는 동안 Transaction을 쥐고 있지 않다.
	 * <p>
	 * 맥락 하나에 GitHub 호출이 여러 번 나가므로 최악의 경우 수십 초가 걸린다. 그동안 DB 연결과 잠금을
	 * 쥐고 있으면 수집 요청 몇 개가 연결 풀을 비워 Incident 조회와 확인 처리가 함께 막힌다.
	 */
	@Test
	void callsGitHubWithoutHoldingATransaction() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));

		collect(incident.getId(), status().isOk());

		assertThat(this.gitHubClient.calledInsideTransaction()).isFalse();
	}

	/**
	 * 맥락을 모아도 Incident는 한 글자도 바뀌지 않는다. GitHub은 탐지기가 아니며, 여기서 Incident를
	 * 고치면 그 순간부터 외부 저장소의 내용이 판정에 참여한다.
	 */
	@Test
	void changesNothingAboutTheIncidentItself() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));
		Instant updatedAtBefore = incident.getUpdatedAt();

		collect(incident.getId(), status().isOk());

		Incident reloaded = this.incidentRepository.findById(incident.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(incident.getStatus());
		assertThat(reloaded.getSeverity()).isEqualTo(incident.getSeverity());
		assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAtBefore);
		assertThat(reloaded.getAcknowledgedAt()).isNull();
		assertThat(reloaded.getResolvedAt()).isNull();
	}

	/** 판정 상태도 그대로다. 맥락 수집이 다음 창의 판정에 영향을 주면 안 된다. */
	@Test
	void changesNothingAboutTheDetectionState() throws Exception {
		Incident incident = save(open(IncidentType.ERROR_RATE));
		long before = this.detectionStateRepository.count();

		collect(incident.getId(), status().isOk());

		assertThat(this.detectionStateRepository.count()).isEqualTo(before);
	}

	/** 이미 끝난 Incident도 수집할 수 있다. 지난 장애를 사후에 들여다보는 것이 이 기능의 주된 쓰임이다. */
	@Test
	void collectsForAnIncidentThatIsAlreadyResolved() throws Exception {
		Incident incident = open(IncidentType.LATENCY);
		incident.resolve(OPENED_AT.plusSeconds(600));
		Incident saved = save(incident);

		collect(saved.getId(), status().isOk());

		assertThat(this.incidentGitHubContextRepository.count()).isEqualTo(1);
	}

	private JsonNode collect(Long incidentId, ResultMatcher expectedStatus) throws Exception {
		return body(this.mockMvc.perform(post("/api/incidents/{incidentId}/github-context", incidentId))
			.andExpect(expectedStatus)
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	private JsonNode read(Long incidentId, ResultMatcher expectedStatus) throws Exception {
		return body(this.mockMvc.perform(get("/api/incidents/{incidentId}/github-context", incidentId))
			.andExpect(expectedStatus)
			.andReturn()
			.getResponse()
			.getContentAsString());
	}

	private JsonNode body(String content) {
		return this.objectMapper.readTree(content);
	}

	private Incident save(Incident incident) {
		return this.incidentRepository.saveAndFlush(incident);
	}

	private static Incident open(IncidentType type) {
		return Incident.open(type, IncidentSeverity.CRITICAL,
				new MetricSnapshot(SERVICE, METHOD, ENDPOINT, WINDOW_START, OPENED_AT, 170, 140, 82.35, 180L),
				OPENED_AT);
	}

}

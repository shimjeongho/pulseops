package com.pulseops.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestClient;

/**
 * GitHub을 실제로 부르지 않고 이 Client가 무엇을 보내고 무엇을 받아들이는지 고정한다.
 * <p>
 * 진짜 GitHub을 부르지 않는다. 부르면 인터넷이 없는 곳에서 테스트가 돌지 않고, 분당 열 번뿐인 code
 * search 한도를 테스트가 갉아먹으며, 남의 repository 내용이 바뀔 때마다 결과가 달라진다. 여기서 확인할
 * 것은 GitHub이 무엇을 갖고 있는지가 아니라 우리 쪽 계약이다.
 * <p>
 * token은 가짜 값이다. 자동 테스트에 진짜 credential이 필요하면 그 순간 credential이 없는 곳에서는
 * 테스트가 돌지 않게 되고, 어딘가에 진짜 값을 적어 두려는 압력이 생긴다.
 */
class GitHubRestIncidentContextClientTest {

	private static final String DUMMY_TOKEN = "ghp_dummy_not_a_real_token";

	private static final String OWNER = "pulseops-test";

	private static final String REPOSITORY = "pulseops";

	private static final String REF = "main";

	private static final String SOURCE_ROOT = "demo-shop/backend/src/main/java";

	private static final String CONTROLLER_PATH = SOURCE_ROOT + "/com/pulseops/demoshop/order/OrderController.java";

	private static final String SERVICE_PATH = SOURCE_ROOT + "/com/pulseops/demoshop/order/OrderService.java";

	private static final String ENDPOINT = "/api/orders/{orderId}";

	private static final Instant OPENED_AT = Instant.parse("2026-09-01T06:00:00Z");

	private static final String RECENT_SHA = "1111111111111111111111111111111111111111";

	private static final String OLDER_SHA = "2222222222222222222222222222222222222222";

	private static final String CONTROLLER_SOURCE = """
			package com.pulseops.demoshop.order;

			@RestController
			@RequestMapping("/api/orders")
			public class OrderController {

				@GetMapping("/{orderId}")
				public OrderResponse getOrder(@PathVariable Long orderId) {
					return this.orderService.get(orderId);
				}

			}
			""";

	private MockRestServiceServer server;

	private GitHubRestIncidentContextClient client;

	@BeforeEach
	void bindClientToAMockServer() {
		RestClient.Builder builder = RestClient.builder()
			.baseUrl("https://api.github.com")
			.defaultHeader("Authorization", "Bearer " + DUMMY_TOKEN)
			.defaultHeader("Accept", "application/vnd.github+json")
			.defaultHeader("X-GitHub-Api-Version", "2026-03-10");
		this.server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
		this.client = new GitHubRestIncidentContextClient(builder.build(),
				new GitHubProperties(true, DUMMY_TOKEN, OWNER, REPOSITORY, REF, SOURCE_ROOT));
	}

	/**
	 * 공식 문서가 정한 header 계약을 지킨다.
	 * <p>
	 * 판을 명시하는 것이 특히 중요하다. 보내지 않으면 GitHub이 오래된 기본 판으로 처리하므로, 코드가
	 * 아는 판과 실제로 응답하는 판이 달라져도 아무 데도 드러나지 않는다.
	 */
	@Test
	void authenticatesAsBearerAndPinsTheApiVersion() {
		this.server.expect(requestToSearch())
			.andExpect(method(HttpMethod.GET))
			.andExpect(header("Authorization", "Bearer " + DUMMY_TOKEN))
			.andExpect(header("Accept", "application/vnd.github+json"))
			.andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
			.andRespond(withSuccess(oneControllerResult(), MediaType.APPLICATION_JSON));
		expectCommits(CONTROLLER_PATH, recentCommitOnly());
		expectContents(CONTROLLER_PATH, RECENT_SHA, CONTROLLER_SOURCE);

		this.client.collect(query());

		this.server.verify();
	}

	/** 이 기능은 GitHub에 아무것도 쓰지 않는다. 모든 호출이 조회여야 한다. */
	@Test
	void onlyEverReadsFromGitHub() {
		this.server.expect(ExpectedCount.manyTimes(), method(HttpMethod.GET))
			.andRespond(withSuccess(noResults(), MediaType.APPLICATION_JSON));

		this.client.collect(query());

		this.server.verify();
	}

	/**
	 * 검색을 설정된 repository와 source root 안으로 못 박는다. 이 한정자가 빠지면 같은 검색어가 GitHub
	 * 전체를 훑고, 관계없는 남의 코드가 우리 Incident의 근거로 저장된다.
	 */
	@Test
	void confinesTheSearchToTheConfiguredRepositoryAndSourceRoot() {
		this.server.expect(MockRestRequestMatchers.queryParam("per_page", "20"))
			.andExpect(method(HttpMethod.GET))
			.andExpect((request) -> {
				String query = decodedQuery(request.getURI().getRawQuery());
				assertThat(request.getURI().getPath()).isEqualTo("/search/code");
				assertThat(query).contains("repo:" + OWNER + "/" + REPOSITORY);
				assertThat(query).contains("path:" + SOURCE_ROOT);
				assertThat(query).contains("extension:java");
				assertThat(query).contains("/api/orders");
			})
			.andRespond(withSuccess(oneControllerResult(), MediaType.APPLICATION_JSON));
		expectCommits(CONTROLLER_PATH, recentCommitOnly());
		expectContents(CONTROLLER_PATH, RECENT_SHA, CONTROLLER_SOURCE);

		this.client.collect(query());

		this.server.verify();
	}

	/**
	 * 정적 접두사로 찾지 못하면 마지막 조각으로 한 번만 더 시도한다. 무한히 넓히면 검색은 언제나 무언가를
	 * 찾아내지만 그것이 관련 있다는 보장은 사라지고, 분당 열 번뿐인 한도만 빠르게 소모한다.
	 */
	@Test
	void fallsBackToTheLastSegmentExactlyOnce() {
		this.server.expect(ExpectedCount.twice(), requestToSearch()).andRespond(withSuccess(noResults(), MediaType.APPLICATION_JSON));

		GitHubCodeContext context = this.client.collect(query());

		assertThat(context.hasNoSource()).isTrue();
		this.server.verify();
	}

	/** 찾은 것이 없어도 맥락은 만들어진다. 실패가 아니라 정상적인 검색 결과이다. */
	@Test
	void returnsAnEmptyContextWhenNothingMatches() {
		this.server.expect(ExpectedCount.twice(), requestToSearch()).andRespond(withSuccess(noResults(), MediaType.APPLICATION_JSON));

		GitHubCodeContext context = this.client.collect(query());

		assertThat(context.repository()).isEqualTo(OWNER + "/" + REPOSITORY);
		assertThat(context.ref()).isEqualTo(REF);
		assertThat(context.files()).isEmpty();
		assertThat(context.commits()).isEmpty();
	}

	/**
	 * source root 밖의 결과와 application source가 아닌 결과를 고르지 않는다.
	 * <p>
	 * 확장자 allowlist가 secret을 막는 1차 경계이다. GitHub이 {@code .env}나 운영 설정 yml, 개인 key를
	 * 결과로 돌려주더라도 그것들은 파일을 고르는 단계에서 전부 빠진다.
	 */
	@Test
	void neverPicksSecretsOrFilesOutsideTheSourceRoot() {
		String results = """
				{"items":[
					{"path":"demo-shop/backend/src/main/resources/application-prod.yml"},
					{"path":".env"},
					{"path":"infra/private.pem"},
					{"path":"../../../etc/passwd"},
					{"path":"other-service/src/main/java/OrderController.java"},
					{"path":"%s"}
				]}
				""".formatted(CONTROLLER_PATH);
		expectSearch(results);
		expectCommits(CONTROLLER_PATH, recentCommitOnly());
		expectContents(CONTROLLER_PATH, RECENT_SHA, CONTROLLER_SOURCE);

		GitHubCodeContext context = this.client.collect(query());

		assertThat(context.files()).hasSize(1);
		assertThat(context.files().get(0).path()).isEqualTo(CONTROLLER_PATH);
	}

	/**
	 * 결과가 아무리 많아도 정해진 수만 고른다. 그리고 그 순서는 실행마다 같다. 검색 점수로 고르면 같은
	 * Incident를 두 번 수집했을 때 다른 파일이 저장될 수 있다.
	 */
	@Test
	void picksABoundedAndStableSetOfFiles() {
		StringBuilder items = new StringBuilder("{\"items\":[");
		for (int index = 0; index < 10; index++) {
			items.append("{\"path\":\"%s/Zz%dHelper.java\"},".formatted(SOURCE_ROOT, index));
		}
		items.append("{\"path\":\"%s\"},".formatted(SERVICE_PATH));
		items.append("{\"path\":\"%s\"}]}".formatted(CONTROLLER_PATH));
		expectSearch(items.toString());
		expectCommits(CONTROLLER_PATH, recentCommitOnly());
		expectContents(CONTROLLER_PATH, RECENT_SHA, CONTROLLER_SOURCE);
		expectCommits(SERVICE_PATH, recentCommitOnly());
		expectContents(SERVICE_PATH, RECENT_SHA, "class OrderService { // /api/orders }");
		expectCommits(SOURCE_ROOT + "/Zz0Helper.java", noCommits());

		GitHubCodeContext context = this.client.collect(query());

		assertThat(context.files()).hasSizeLessThanOrEqualTo(GitHubCodeContext.MAX_FILES);
		assertThat(context.files()).extracting(GitHubSourceFile::path)
			.containsExactly(CONTROLLER_PATH, SERVICE_PATH);
	}

	/**
	 * Incident 이후의 commit을 원인 후보로 섞지 않는다. 그런 commit은 대개 그 Incident를 고치려던 수정이며,
	 * 결과를 보고 원인을 되짚는 순환이 된다.
	 */
	@Test
	void asksOnlyForCommitsMadeBeforeTheIncidentOpened() {
		expectSearch(oneControllerResult());
		this.server.expect(method(HttpMethod.GET)).andExpect((request) -> {
			String query = decodedQuery(request.getURI().getRawQuery());
			assertThat(request.getURI().getPath()).isEqualTo("/repos/%s/%s/commits".formatted(OWNER, REPOSITORY));
			assertThat(query).contains("until=" + OPENED_AT);
			assertThat(query).contains("path=" + CONTROLLER_PATH);
			assertThat(query).contains("sha=" + REF);
		}).andRespond(withSuccess(recentCommitOnly(), MediaType.APPLICATION_JSON));
		expectContents(CONTROLLER_PATH, RECENT_SHA, CONTROLLER_SOURCE);

		this.client.collect(query());

		this.server.verify();
	}

	/** 서버가 경계를 지키지 않고 뒤늦은 commit을 돌려줘도 우리가 다시 거른다. */
	@Test
	void dropsACommitThatIsNewerThanTheIncidentEvenIfTheServerReturnsIt() {
		String afterIncident = commitsJson(
				commitJson("3333333333333333333333333333333333333333", "Incident 이후 수정", OPENED_AT.plusSeconds(600)),
				commitJson(RECENT_SHA, "주문 검증을 고쳤다", OPENED_AT.minusSeconds(3600)));
		expectSearch(oneControllerResult());
		expectCommits(CONTROLLER_PATH, afterIncident);
		expectContents(CONTROLLER_PATH, RECENT_SHA, CONTROLLER_SOURCE);

		GitHubCodeContext context = this.client.collect(query());

		assertThat(context.commits()).extracting(GitHubCommit::sha).containsExactly(RECENT_SHA);
	}

	/**
	 * 파일 내용을 기본 branch의 현재 판이 아니라 Incident 직전 commit에서 읽는다. 현재 판을 읽으면
	 * 이미 고쳐진 코드를 보고 그 Incident의 원인을 찾는 셈이 된다.
	 */
	@Test
	void readsTheFileAsItWasJustBeforeTheIncident() {
		expectSearch(oneControllerResult());
		expectCommits(CONTROLLER_PATH, recentCommitOnly());
		this.server.expect(method(HttpMethod.GET)).andExpect((request) -> {
			assertThat(request.getURI().getPath())
				.isEqualTo("/repos/%s/%s/contents/%s".formatted(OWNER, REPOSITORY, CONTROLLER_PATH));
			assertThat(decodedQuery(request.getURI().getRawQuery())).isEqualTo("ref=" + RECENT_SHA);
		}).andRespond(withSuccess(contentsJson(CONTROLLER_PATH, RECENT_SHA, CONTROLLER_SOURCE), MediaType.APPLICATION_JSON));

		GitHubCodeContext context = this.client.collect(query());

		assertThat(context.files().get(0).revisionSha()).isEqualTo(RECENT_SHA);
	}

	/**
	 * 최근이라 부를 구간 밖의 commit은 맥락에 담지 않는다. 파일을 어느 판에서 읽을지는 그 구간과 무관하게
	 * 그 시점의 마지막 commit이 정하므로, 오래된 commit 하나뿐이어도 조각은 그 판에서 만들어진다.
	 */
	@Test
	void keepsTheOldRevisionButNotTheOldCommitAsRecentChange() {
		String old = commitsJson(commitJson(OLDER_SHA, "한참 전 변경", OPENED_AT.minus(GitHubRestIncidentContextClient.RECENT_COMMIT_WINDOW).minusSeconds(60)));
		expectSearch(oneControllerResult());
		expectCommits(CONTROLLER_PATH, old);
		expectContents(CONTROLLER_PATH, OLDER_SHA, CONTROLLER_SOURCE);

		GitHubCodeContext context = this.client.collect(query());

		assertThat(context.files().get(0).revisionSha()).isEqualTo(OLDER_SHA);
		assertThat(context.commits()).isEmpty();
	}

	/**
	 * Incident 시점 이전에 이 경로를 건드린 commit이 없으면 그때는 없던 파일이다. 지금 내용을 대신 읽으면
	 * Incident 당시 존재하지도 않던 코드가 근거로 저장된다.
	 */
	@Test
	void skipsAFileThatDidNotExistWhenTheIncidentOpened() {
		expectSearch(oneControllerResult());
		expectCommits(CONTROLLER_PATH, noCommits());

		GitHubCodeContext context = this.client.collect(query());

		assertThat(context.files()).isEmpty();
		this.server.verify();
	}

	/** 그 판에서 파일이 지워졌다면 그 파일만 건너뛴다. 맥락 전체를 잃을 이유가 없다. */
	@Test
	void skipsOnlyTheFileThatIsMissingAtThatRevision() {
		expectSearch(twoResults());
		expectCommits(CONTROLLER_PATH, recentCommitOnly());
		this.server.expect(requestTo(contentsUrl(CONTROLLER_PATH, RECENT_SHA)))
			.andRespond(withStatus(HttpStatus.NOT_FOUND));
		expectCommits(SERVICE_PATH, recentCommitOnly());
		expectContents(SERVICE_PATH, RECENT_SHA, "class OrderService { // /api/orders }");

		GitHubCodeContext context = this.client.collect(query());

		assertThat(context.files()).extracting(GitHubSourceFile::path).containsExactly(SERVICE_PATH);
	}

	/**
	 * 한도를 넘긴 응답에서 곧바로 다시 부르지 않는다. 이미 한도를 넘긴 곳에 요청을 더 보내는 일이며,
	 * 공식 문서도 그것을 금지한다. 다시 시도할지는 사람이 정한다.
	 */
	@Test
	void doesNotRetryWhenTheRateLimitIsExhausted() {
		HttpHeaders headers = new HttpHeaders();
		headers.add("x-ratelimit-remaining", "0");
		this.server.expect(ExpectedCount.once(), requestToSearch())
			.andRespond(withStatus(HttpStatus.FORBIDDEN).headers(headers));

		assertThatExceptionOfType(IncidentGitHubContextUnavailableException.class)
			.isThrownBy(() -> this.client.collect(query()))
			.satisfies((failure) -> assertThat(failure.getReason()).isEqualTo("provider_rate_limited"));
		this.server.verify();
	}

	@Test
	void treatsTooManyRequestsAsARateLimitWithoutRetrying() {
		this.server.expect(ExpectedCount.once(), requestToSearch()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		assertThatExceptionOfType(IncidentGitHubContextUnavailableException.class)
			.isThrownBy(() -> this.client.collect(query()))
			.satisfies((failure) -> assertThat(failure.getReason()).isEqualTo("provider_rate_limited"));
		this.server.verify();
	}

	/** 인증 실패와 서버 오류는 한 번만 시도하고 그대로 실패로 알린다. */
	@ParameterizedTest
	@ValueSource(ints = { 401, 422, 500, 503 })
	void failsOnceWithoutRetryingOnOtherErrors(int status) {
		this.server.expect(ExpectedCount.once(), requestToSearch()).andRespond(withStatus(HttpStatus.valueOf(status)));

		assertThatExceptionOfType(IncidentGitHubContextUnavailableException.class)
			.isThrownBy(() -> this.client.collect(query()))
			.satisfies((failure) -> assertThat(failure.getReason()).isEqualTo("provider_http_" + status));
		this.server.verify();
	}

	@Test
	void failsWhenGitHubCannotBeReached() {
		this.server.expect(ExpectedCount.once(), requestToSearch())
			.andRespond(withException(new SocketTimeoutException("read timed out")));

		assertThatExceptionOfType(IncidentGitHubContextUnavailableException.class)
			.isThrownBy(() -> this.client.collect(query()))
			.satisfies((failure) -> assertThat(failure.getReason()).isEqualTo("provider_unreachable"));
	}

	/**
	 * 실패를 알리는 어디에도 token이 없다.
	 * <p>
	 * 예외의 message는 log와 상위 예외를 거쳐 여러 곳에 남는다. 그 자리에 credential이 한 번이라도 담기면
	 * 그것을 되돌릴 방법은 없다.
	 */
	@Test
	void neverPutsTheTokenIntoAFailure() {
		this.server.expect(requestToSearch())
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"message\":\"Bad credentials\"}"));

		assertThatExceptionOfType(IncidentGitHubContextUnavailableException.class)
			.isThrownBy(() -> this.client.collect(query()))
			.satisfies((failure) -> {
				assertThat(failure.getMessage()).doesNotContain(DUMMY_TOKEN);
				assertThat(failure.getReason()).doesNotContain(DUMMY_TOKEN);
				// provider가 돌려준 본문도 예외에 담기지 않는다
				assertThat(failure.getMessage()).doesNotContain("Bad credentials");
			});
	}

	/**
	 * 맥락 하나에 드는 호출 수를 못 박는다. 검색 한 번과 파일마다 둘이므로 파일 셋이면 일곱 번이다.
	 * 이 수가 늘면 분당 한도를 언제 넘길지 아무도 예측할 수 없다.
	 */
	@Test
	void keepsTheNumberOfCallsBounded() {
		expectSearch(twoResults());
		expectCommits(CONTROLLER_PATH, recentCommitOnly());
		expectContents(CONTROLLER_PATH, RECENT_SHA, CONTROLLER_SOURCE);
		expectCommits(SERVICE_PATH, recentCommitOnly());
		expectContents(SERVICE_PATH, RECENT_SHA, "class OrderService { // /api/orders }");

		this.client.collect(query());

		// 더 부르면 남지 않은 기대를 쓰게 되어 검증에서 드러난다
		this.server.verify();
	}

	private IncidentGitHubContextQuery query() {
		return new IncidentGitHubContextQuery(ENDPOINT, OPENED_AT);
	}

	private void expectSearch(String body) {
		this.server.expect(requestToSearch()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

	private org.springframework.test.web.client.RequestMatcher requestToSearch() {
		return (request) -> assertThat(request.getURI().getPath()).isEqualTo("/search/code");
	}

	private void expectCommits(String path, String body) {
		this.server.expect((request) -> {
			assertThat(request.getURI().getPath()).isEqualTo("/repos/%s/%s/commits".formatted(OWNER, REPOSITORY));
			assertThat(decodedQuery(request.getURI().getRawQuery())).contains("path=" + path);
		}).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

	private void expectContents(String path, String sha, String source) {
		this.server.expect(requestTo(contentsUrl(path, sha)))
			.andRespond(withSuccess(contentsJson(path, sha, source), MediaType.APPLICATION_JSON));
	}

	private static String contentsUrl(String path, String sha) {
		return "https://api.github.com/repos/%s/%s/contents/%s?ref=%s".formatted(OWNER, REPOSITORY, path, sha);
	}

	private static String decodedQuery(String rawQuery) {
		return (rawQuery != null) ? URLDecoder.decode(rawQuery, StandardCharsets.UTF_8) : "";
	}

	private static String oneControllerResult() {
		return "{\"items\":[{\"path\":\"%s\"}]}".formatted(CONTROLLER_PATH);
	}

	private static String twoResults() {
		return "{\"items\":[{\"path\":\"%s\"},{\"path\":\"%s\"}]}".formatted(SERVICE_PATH, CONTROLLER_PATH);
	}

	private static String noResults() {
		return "{\"items\":[]}";
	}

	private static String noCommits() {
		return "[]";
	}

	private static String recentCommitOnly() {
		return commitsJson(commitJson(RECENT_SHA, "주문 검증을 고쳤다", OPENED_AT.minusSeconds(3600)));
	}

	private static String commitsJson(String... commits) {
		return "[" + String.join(",", commits) + "]";
	}

	private static String commitJson(String sha, String message, Instant committedAt) {
		return """
				{"sha":"%s","commit":{"message":"%s","author":{"name":"someone","email":"someone@example.com",\
				"date":"%s"},"committer":{"name":"someone","email":"someone@example.com","date":"%s"}}}\
				""".formatted(sha, message, committedAt, committedAt);
	}

	private static String contentsJson(String path, String sha, String source) {
		String encoded = Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8));
		return """
				{"path":"%s","sha":"%s","size":%d,"encoding":"base64","content":"%s"}\
				""".formatted(path, sha, source.length(), encoded);
	}

}

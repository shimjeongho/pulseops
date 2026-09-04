package com.pulseops.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.pulseops.github.GitHubCodeContext;
import com.pulseops.github.GitHubCommit;
import com.pulseops.github.GitHubSourceFile;
import com.pulseops.incident.IncidentSeverity;
import com.pulseops.incident.IncidentStatus;
import com.pulseops.incident.IncidentType;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Messages API를 실제로 부르지 않고 이 Client가 무엇을 보내고 무엇을 받아들이는지 고정한다.
 * <p>
 * 진짜 Claude를 부르지 않는다. 부르면 테스트를 돌릴 때마다 요금이 나가고, 같은 입력에 같은 답이 오지
 * 않아 무엇이 깨졌는지 알 수 없게 되며, 인터넷이 없는 곳에서는 아예 돌지 않는다. 여기서 확인할 것은
 * 모델이 좋은 답을 하는지가 아니라 우리 쪽 계약이다.
 * <p>
 * API key는 가짜 값이다. 자동 테스트에 진짜 credential이 필요하면 그 순간 credential이 없는 곳에서는
 * 테스트가 돌지 않게 되고, 어딘가에 진짜 값을 적어 두려는 압력이 생긴다.
 */
class ClaudeIncidentAnalysisClientTest {

	private static final String DUMMY_API_KEY = "sk-ant-dummy-not-a-real-key";

	private static final String MODEL = "claude-opus-5";

	/** 응답이 알려 주는 model이다. 설정값과 일부러 다르게 두어 어느 쪽이 저장되는지 드러나게 한다. */
	private static final String SERVED_MODEL = "claude-opus-5-served";

	private static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";

	private static final String CODE_PATH = "demo-shop/backend/src/main/java/com/pulseops/demoshop/order/OrderController.java";

	private static final String CODE_SHA = "1111111111111111111111111111111111111111";

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private MockRestServiceServer server;

	private ClaudeIncidentAnalysisClient client;

	@BeforeEach
	void bindClientToAMockServer() {
		RestClient.Builder builder = RestClient.builder()
			.baseUrl("https://api.anthropic.com")
			.defaultHeader("x-api-key", DUMMY_API_KEY)
			.defaultHeader("anthropic-version", "2023-06-01");
		this.server = MockRestServiceServer.bindTo(builder).build();
		this.client = new ClaudeIncidentAnalysisClient(builder.build(), MODEL);
	}

	/** 인증과 계약 판을 header로 보낸다. 이 둘이 없으면 어떤 요청도 성립하지 않는다. */
	@Test
	void authenticatesWithTheConfiguredKeyAndPinsTheApiVersion() {
		this.server.expect(requestTo(MESSAGES_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("x-api-key", DUMMY_API_KEY))
			.andExpect(header("anthropic-version", "2023-06-01"))
			.andRespond(withSuccess(response(analysisJson()), MediaType.APPLICATION_JSON));

		this.client.analyze(errorRateFacts());

		this.server.verify();
	}

	/**
	 * 요청 본문의 모양을 고정한다.
	 * <p>
	 * 출력 한도를 함께 본다. 한도가 없으면 분석이 얼마든지 길어질 수 있고, 한도가 사라진 것은 응답이
	 * 길어지기 전까지 아무 데도 드러나지 않는다.
	 */
	@Test
	void sendsTheConfiguredModelABoundedOutputAndTheOutputSchema() {
		AtomicReference<JsonNode> body = expectRequest(response(analysisJson()));

		this.client.analyze(errorRateFacts());

		JsonNode request = body.get();
		assertThat(request.get("model").stringValue()).isEqualTo(MODEL);
		assertThat(request.get("max_tokens").intValue()).isEqualTo(ClaudeIncidentAnalysisClient.MAX_OUTPUT_TOKENS);
		assertThat(request.get("output_config").get("format").get("type").stringValue()).isEqualTo("json_schema");
		assertThat(request.get("output_config").get("format").get("schema").get("additionalProperties").booleanValue())
			.isFalse();
	}

	/** 지시는 system에, Incident 사실은 사용자 message에만 있어야 한다. */
	@Test
	void sendsInstructionsAndFactsInSeparatePlaces() {
		AtomicReference<JsonNode> body = expectRequest(response(analysisJson()));

		this.client.analyze(errorRateFacts());

		JsonNode request = body.get();
		assertThat(request.get("system").stringValue()).isEqualTo(IncidentAnalysisPrompt.SYSTEM_INSTRUCTION);
		assertThat(request.get("messages").size()).isEqualTo(1);
		assertThat(request.get("messages").get(0).get("role").stringValue()).isEqualTo("user");
		assertThat(request.get("messages").get(0).get("content").stringValue())
			.contains(IncidentAnalysisPrompt.factsJson(errorRateFacts()));
	}

	/**
	 * model마다 허용 여부가 다른 값은 보내지 않는다. 설정으로 받은 model이 무엇이든 요청 자체는 성립해야
	 * 하는데, 이 값들은 어떤 model에서는 400으로 거절된다.
	 */
	@Test
	void sendsNoModelSpecificTuningParameters() {
		AtomicReference<JsonNode> body = expectRequest(response(analysisJson()));

		this.client.analyze(errorRateFacts());

		assertThat(body.get().propertyNames()).containsExactlyInAnyOrder("model", "max_tokens", "system", "messages",
				"output_config");
	}

	@Test
	void parsesAWellFormedAnalysis() {
		expectRequest(response(analysisJson()));

		IncidentAnalysisResult result = this.client.analyze(errorRateFacts());

		assertThat(result.summary()).isEqualTo("오류율이 급격히 올랐다.");
		assertThat(result.primaryHypothesis()).isEqualTo("결제 의존 서비스의 실패가 의심된다.");
		assertThat(result.confidence()).isEqualTo(AnalysisConfidence.MEDIUM);
		assertThat(result.evidence()).containsExactly("Trigger window의 error rate가 82.35%였다.");
		assertThat(result.recommendedActions()).containsExactly("해당 endpoint의 서버 로그를 확인한다.");
		assertThat(result.limitations()).containsExactly("application log가 주어지지 않았다.");
	}

	/** 설정에 적힌 이름이 아니라 응답이 알려 준 model을 남긴다. 설정에는 alias가 들어갈 수 있다. */
	@Test
	void recordsTheModelThatActuallyAnswered() {
		expectRequest(response(analysisJson()));

		IncidentAnalysisResult result = this.client.analyze(errorRateFacts());

		assertThat(result.model()).isEqualTo(SERVED_MODEL);
	}

	/** 저장할 snapshot은 실제로 보낸 것이어야 한다. 부르는 쪽이 따로 만들면 두 문자열이 언젠가 어긋난다. */
	@Test
	void reportsTheFactsItActuallySentAsTheSnapshot() {
		expectRequest(response(analysisJson()));

		IncidentAnalysisResult result = this.client.analyze(errorRateFacts());

		assertThat(result.inputSnapshot()).isEqualTo(IncidentAnalysisPrompt.factsJson(errorRateFacts()));
		assertThat(result.promptVersion()).isEqualTo(IncidentAnalysisPrompt.VERSION);
	}

	/**
	 * 인증 실패, 권한 없음, 한도 초과, 서버 오류를 모두 같은 실패로 옮긴다. 부르는 쪽이 넷을 두고 할 수
	 * 있는 일이 같고, 넷 다 여기서 다시 시도해서는 안 되는 상황이다.
	 */
	@ParameterizedTest
	@ValueSource(ints = { 401, 403, 429, 500, 529 })
	void turnsEveryProviderErrorStatusIntoTheSameFailure(int status) {
		this.server.expect(requestTo(MESSAGES_URL))
			.andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
				.body("{\"type\":\"error\",\"error\":{\"type\":\"x\",\"message\":\"내부 사정\"}}"));

		assertThatExceptionOfType(IncidentAnalysisUnavailableException.class)
			.isThrownBy(() -> this.client.analyze(errorRateFacts()))
			.satisfies((ex) -> assertThat(ex.getReason()).isEqualTo("provider_http_" + status));
	}

	/** 한도 초과에도 스스로 다시 부르지 않는다. 이미 한도를 넘긴 곳에 요청을 더 보내는 셈이 된다. */
	@Test
	void doesNotRetryOnItsOwn() {
		this.server.expect(ExpectedCount.once(), requestTo(MESSAGES_URL))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		assertThatExceptionOfType(IncidentAnalysisUnavailableException.class)
			.isThrownBy(() -> this.client.analyze(errorRateFacts()));

		this.server.verify();
	}

	/** provider가 돌려준 문장이 예외를 타고 밖으로 나가면 안 된다. */
	@Test
	void keepsProviderErrorDetailOutOfTheFailure() {
		this.server.expect(requestTo(MESSAGES_URL))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"message\":\"invalid x-api-key sk-ant-dummy-not-a-real-key\"}}"));

		assertThatExceptionOfType(IncidentAnalysisUnavailableException.class)
			.isThrownBy(() -> this.client.analyze(errorRateFacts()))
			.satisfies((ex) -> assertThat(ex.getMessage()).doesNotContain(DUMMY_API_KEY).doesNotContain("sk-ant"));
	}

	@Test
	void turnsATimeoutIntoAFailure() {
		this.server.expect(requestTo(MESSAGES_URL)).andRespond(withException(new SocketTimeoutException("read timed out")));

		assertThatExceptionOfType(IncidentAnalysisUnavailableException.class)
			.isThrownBy(() -> this.client.analyze(errorRateFacts()))
			.satisfies((ex) -> assertThat(ex.getReason()).isEqualTo("provider_unreachable"));
	}

	@Test
	void turnsANetworkFailureIntoAFailure() {
		this.server.expect(requestTo(MESSAGES_URL)).andRespond(withException(new IOException("connection reset")));

		assertThatExceptionOfType(IncidentAnalysisUnavailableException.class)
			.isThrownBy(() -> this.client.analyze(errorRateFacts()))
			.satisfies((ex) -> assertThat(ex.getReason()).isEqualTo("provider_unreachable"));
	}

	/**
	 * 길이 제한에 걸려 잘린 응답은 HTTP로는 성공이고 JSON 구조도 갖추고 있을 수 있다. 그것을 저장하면
	 * 문장이 중간에서 끊긴 분석이 정상 결과로 남는다.
	 */
	@Test
	void rejectsATruncatedResponse() {
		expectRequest(response(analysisJson(), "max_tokens"));

		assertThatExceptionOfType(IncidentAnalysisUnavailableException.class)
			.isThrownBy(() -> this.client.analyze(errorRateFacts()))
			.satisfies((ex) -> assertThat(ex.getReason()).isEqualTo("provider_incomplete_response"));
	}

	/** 분석 본문 조각을 종류로 고른다. 본문 조각이 없으면 무엇을 분석으로 읽을지 정할 근거가 없다. */
	@Test
	void rejectsAResponseWithoutATextBlock() {
		expectRequest("""
				{"model":"%s","stop_reason":"end_turn","content":[{"type":"thinking","thinking":"음"}]}
				""".formatted(SERVED_MODEL));

		assertThatExceptionOfType(IncidentAnalysisUnavailableException.class)
			.isThrownBy(() -> this.client.analyze(errorRateFacts()))
			.satisfies((ex) -> assertThat(ex.getReason()).isEqualTo("provider_unexpected_content"));
	}

	/** 본문 조각이 둘이면 어느 것이 분석인지 우리가 정할 근거가 없다. 아무거나 고르지 않는다. */
	@Test
	void rejectsAResponseWithMoreThanOneTextBlock() {
		expectRequest("""
				{"model":"%s","stop_reason":"end_turn","content":[
					{"type":"text","text":%s},
					{"type":"text","text":"덧붙임"}
				]}
				""".formatted(SERVED_MODEL, quote(analysisJson())));

		assertThatExceptionOfType(IncidentAnalysisUnavailableException.class)
			.isThrownBy(() -> this.client.analyze(errorRateFacts()))
			.satisfies((ex) -> assertThat(ex.getReason()).isEqualTo("provider_unexpected_content"));
	}

	@Test
	void rejectsAnalysisThatIsNotJson() {
		expectRequest(response("이건 JSON이 아니라 그냥 문장입니다."));

		assertThatExceptionOfType(IncidentAnalysisUnavailableException.class)
			.isThrownBy(() -> this.client.analyze(errorRateFacts()))
			.satisfies((ex) -> assertThat(ex.getReason()).isEqualTo("provider_unparsable_analysis"));
	}

	/** 계약에 없던 field가 왔다는 것은 계약이 지켜지지 않았다는 뜻이다. 조용히 버리지 않는다. */
	@Test
	void rejectsAnalysisWithAnUnknownField() {
		expectRequest(response("""
				{"summary":"요약","primaryHypothesis":"가설","confidence":"LOW","evidence":["근거"],
				 "recommendedActions":["조치"],"limitations":["한계"],"rootCauseConfirmed":true}
				"""));

		assertThatExceptionOfType(IncidentAnalysisUnavailableException.class)
			.isThrownBy(() -> this.client.analyze(errorRateFacts()))
			.satisfies((ex) -> assertThat(ex.getReason()).isEqualTo("provider_unparsable_analysis"));
	}

	@Test
	void rejectsAnalysisWithAnUnknownConfidence() {
		expectRequest(response("""
				{"summary":"요약","primaryHypothesis":"가설","confidence":"VERY_HIGH","evidence":["근거"],
				 "recommendedActions":["조치"],"limitations":["한계"]}
				"""));

		assertThatExceptionOfType(IncidentAnalysisUnavailableException.class)
			.isThrownBy(() -> this.client.analyze(errorRateFacts()))
			.satisfies((ex) -> assertThat(ex.getReason()).isEqualTo("provider_unknown_confidence"));
	}

	@Test
	void rejectsAnalysisWithAMissingField() {
		expectRequest(response("""
				{"summary":"요약","primaryHypothesis":"가설","confidence":"LOW","evidence":["근거"],
				 "recommendedActions":["조치"]}
				"""));

		assertThatExceptionOfType(IncidentAnalysisUnavailableException.class)
			.isThrownBy(() -> this.client.analyze(errorRateFacts()))
			.satisfies((ex) -> assertThat(ex.getReason()).isEqualTo("provider_invalid_analysis"));
	}

	@Test
	void rejectsAnalysisWithABlankSummary() {
		expectRequest(response("""
				{"summary":"   ","primaryHypothesis":"가설","confidence":"LOW","evidence":["근거"],
				 "recommendedActions":["조치"],"limitations":["한계"]}
				"""));

		assertThatExceptionOfType(IncidentAnalysisUnavailableException.class)
			.isThrownBy(() -> this.client.analyze(errorRateFacts()))
			.satisfies((ex) -> assertThat(ex.getReason()).isEqualTo("provider_invalid_analysis"));
	}

	/** 한계가 비어 있는 분석은 계약 위반이다. 이 분석에는 log도 trace도 없으므로 한계가 없을 수 없다. */
	@Test
	void rejectsAnalysisWithoutLimitations() {
		expectRequest(response("""
				{"summary":"요약","primaryHypothesis":"가설","confidence":"LOW","evidence":["근거"],
				 "recommendedActions":["조치"],"limitations":[]}
				"""));

		assertThatExceptionOfType(IncidentAnalysisUnavailableException.class)
			.isThrownBy(() -> this.client.analyze(errorRateFacts()))
			.satisfies((ex) -> assertThat(ex.getReason()).isEqualTo("provider_invalid_analysis"));
	}

	/**
	 * 코드 맥락까지 보내는 호출은 다른 지시와 다른 판을 쓴다.
	 * <p>
	 * 두 분석이 같은 지시를 쓰면 저장된 분석이 무엇을 근거로 만들어졌는지 판 번호로 구분할 수 없다.
	 * 반대로 출력을 제약하는 Schema는 같아야 한다. 근거가 늘었다고 답의 모양까지 달라질 이유가 없다.
	 */
	@Test
	void sendsTheCodeAwareInstructionAndItsOwnPromptVersion() {
		AtomicReference<JsonNode> body = expectRequest(response(analysisJson()));

		IncidentAnalysisResult result = this.client.analyzeWithCode(errorRateFacts(), codeContext());

		JsonNode request = body.get();
		assertThat(request.get("system").stringValue()).isEqualTo(IncidentCodeAnalysisPrompt.SYSTEM_INSTRUCTION);
		assertThat(request.get("output_config").get("format").get("schema"))
			.isEqualTo(MAPPER.readTree(IncidentAnalysisPrompt.outputSchema().toString()));
		assertThat(result.promptVersion()).isEqualTo(IncidentCodeAnalysisPrompt.VERSION);
	}

	/** 소스 조각과 commit이 실제로 실려 나간다. 근거가 담기지 않으면 코드 기반 분석이 아니다. */
	@Test
	void carriesTheSourceSnippetAndTheCommitsIntoTheMessage() {
		AtomicReference<JsonNode> body = expectRequest(response(analysisJson()));

		this.client.analyzeWithCode(errorRateFacts(), codeContext());

		String content = body.get().get("messages").get(0).get("content").stringValue();
		assertThat(content).contains(CODE_PATH);
		assertThat(content).contains("OrderController");
		assertThat(content).contains(CODE_SHA);
	}

	/**
	 * 저장되는 snapshot은 코드 맥락이 아니라 Incident 사실이다. 맥락은 이미 별도의 기록으로 저장되어
	 * 있고, 분석 행에 한 번 더 복사해 두면 언젠가 두 값이 서로 다른 것을 가리킨다.
	 */
	@Test
	void keepsTheStoredSnapshotAsTheIncidentFacts() {
		expectRequest(response(analysisJson()));

		IncidentAnalysisResult result = this.client.analyzeWithCode(errorRateFacts(), codeContext());

		assertThat(result.inputSnapshot()).isEqualTo(IncidentAnalysisPrompt.factsJson(errorRateFacts()));
		assertThat(result.inputSnapshot()).doesNotContain(CODE_SHA);
	}

	/** 기본 분석은 코드 맥락이 생긴 뒤에도 그대로 자기 지시와 자기 판으로 만들어진다. */
	@Test
	void leavesTheBaseAnalysisUntouched() {
		AtomicReference<JsonNode> body = expectRequest(response(analysisJson()));

		IncidentAnalysisResult result = this.client.analyze(errorRateFacts());

		assertThat(body.get().get("system").stringValue()).isEqualTo(IncidentAnalysisPrompt.SYSTEM_INSTRUCTION);
		assertThat(result.promptVersion()).isEqualTo(IncidentAnalysisPrompt.VERSION);
	}

	private static GitHubCodeContext codeContext() {
		return new GitHubCodeContext("pulseops-test/pulseops", "main", "demo-shop/backend/src/main/java",
				List.of(new GitHubSourceFile(CODE_PATH, CODE_SHA,
						"@RequestMapping(\"/api/orders\")\npublic class OrderController {}", true)),
				List.of(new GitHubCommit(CODE_SHA, "주문 검증을 고쳤다", Instant.parse("2026-09-01T05:00:00Z"),
						List.of(CODE_PATH))));
	}

	private AtomicReference<JsonNode> expectRequest(String responseBody) {
		AtomicReference<JsonNode> body = new AtomicReference<>();
		this.server.expect(requestTo(MESSAGES_URL))
			.andExpect((request) -> body
				.set(MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsString())))
			.andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
		return body;
	}

	private static String response(String analysisText) {
		return response(analysisText, "end_turn");
	}

	private static String response(String analysisText, String stopReason) {
		return """
				{"id":"msg_1","type":"message","role":"assistant","model":"%s","stop_reason":"%s",
				 "usage":{"input_tokens":1,"output_tokens":2},
				 "content":[{"type":"text","text":%s}]}
				""".formatted(SERVED_MODEL, stopReason, quote(analysisText));
	}

	private static String quote(String value) {
		return MAPPER.writeValueAsString(value);
	}

	private static String analysisJson() {
		return """
				{"summary":"오류율이 급격히 올랐다.",
				 "primaryHypothesis":"결제 의존 서비스의 실패가 의심된다.",
				 "confidence":"MEDIUM",
				 "evidence":["Trigger window의 error rate가 82.35%였다."],
				 "recommendedActions":["해당 endpoint의 서버 로그를 확인한다."],
				 "limitations":["application log가 주어지지 않았다."]}
				""";
	}

	private static IncidentAnalysisFacts errorRateFacts() {
		return new IncidentAnalysisFacts("demo-shop", "POST", "/api/orders", IncidentType.ERROR_RATE,
				IncidentSeverity.CRITICAL, IncidentStatus.OPEN, Instant.parse("2026-09-01T06:00:00Z"),
				Instant.parse("2026-09-01T05:59:00Z"), Instant.parse("2026-09-01T06:00:00Z"), 170, 140,
				new BigDecimal("82.35"), 180L);
	}

}

package com.pulseops.analysis;

import com.pulseops.github.GitHubCodeContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anthropic Messages API를 호출해 Incident 분석을 받아 온다.
 * <p>
 * 공식 Java SDK 대신 이미 있는 {@link RestClient}를 쓴다. 이 기능이 쓰는 것은 Messages API 하나의
 * 요청과 응답이고, 그것을 위해 dependency를 더하면 SDK가 끌고 오는 Jackson 2 계열이 이 Application의
 * Jackson 3와 같은 classpath에 놓인다. 게다가 SDK는 기본으로 실패한 요청을 스스로 다시 보내는데, 그것은
 * 요금이 붙는 호출을 사용자가 모르는 사이에 늘리는 동작이라 여기서는 원하지 않는 성질이다. 직접 부르면
 * 몇 번 부를지를 이 코드가 정한다.
 * <p>
 * 자동 재시도를 하지 않는다. 실패하면 그대로 실패로 알리고 사람이 다시 누르게 한다. 재시도는 요금과
 * rate limit을 곱으로 늘리는데, 이 호출은 사람이 버튼을 눌러 시작한 것이라 다시 시도할 사람이 이미
 * 화면 앞에 있다. Kafka 소비의 재시도와도 아무 관계가 없다. 그쪽은 사람이 없는 자리에서 도는 흐름이라
 * 스스로 다시 시도해야 하지만, 여기는 아니다.
 * <p>
 * 두 종류의 분석을 한 Class가 맡는다. 보내는 지시와 사용자 message가 다를 뿐 인증도 호출 방법도 응답을
 * 검증하는 규칙도 같아서, 나누면 그 규칙이 두 벌이 되고 한쪽만 고쳐진 채로 남는다. 무엇이 다른지는
 * {@code analyze}와 {@code analyzeWithCode}가 서로 다른 prompt를 넘기는 두 줄에만 있다.
 * <p>
 * 실패의 종류를 응답에 담지 않는다. provider가 돌려준 본문에는 요청 내용이나 계정 사정이 담겨 올 수
 * 있어 그대로 밖으로 내보낼 수 없고, log에도 상태 코드와 provider의 요청 식별자만 남긴다. 이 둘이면
 * 필요할 때 provider 쪽 기록과 맞춰 볼 수 있고, 그 이상은 우리 log에 남아서 좋을 것이 없다.
 */
public class ClaudeIncidentAnalysisClient implements IncidentAnalysisClient {

	/**
	 * 한 번의 분석이 만들 수 있는 최대 출력 token이다.
	 * <p>
	 * 분석 자체는 짧다. 요약 세 문장과 배열 셋을 합쳐도 이 값의 일부만 쓴다. 그런데도 넉넉하게 두는
	 * 것은 최근 model이 답을 만들기 전에 스스로 생각하는 단계를 갖고 그 단계도 이 한도를 함께 쓰기
	 * 때문이다. 한도에 걸려 잘린 응답은 저장하지 않고 버리므로, 한도가 빠듯하면 정상적인 분석이
	 * 실패로 처리된다. 반대로 무제한으로 둘 수는 없어 여기서 상한을 정한다.
	 */
	static final int MAX_OUTPUT_TOKENS = 8192;

	private static final String MESSAGES_PATH = "/v1/messages";

	private static final String USER_ROLE = "user";

	/** 실패를 provider 쪽 기록과 맞춰 볼 수 있는 유일한 값이다. 민감한 정보가 아니다. */
	private static final String REQUEST_ID_HEADER = "request-id";

	private static final Logger logger = LoggerFactory.getLogger(ClaudeIncidentAnalysisClient.class);

	/**
	 * 분석 본문 전용 Mapper이다. 모르는 field를 만나면 실패한다.
	 * <p>
	 * 이 구조는 우리가 Schema로 요구한 계약이므로, 없던 field가 왔다는 것은 계약이 지켜지지 않았다는
	 * 뜻이다. 조용히 버리면 계약이 어긋난 채로 분석이 저장되고, 무엇이 달라졌는지는 아무 데도 남지 않는다.
	 */
	private static final ObjectMapper ANALYSIS_MAPPER = JsonMapper.builder()
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build();

	private final RestClient restClient;

	private final String model;

	public ClaudeIncidentAnalysisClient(RestClient restClient, String model) {
		this.restClient = restClient;
		this.model = model;
	}

	@Override
	public IncidentAnalysisResult analyze(IncidentAnalysisFacts facts) {
		String factsJson = IncidentAnalysisPrompt.factsJson(facts);
		ClaudeMessageResponse response = call(request(IncidentAnalysisPrompt.SYSTEM_INSTRUCTION,
				IncidentAnalysisPrompt.userMessage(factsJson)));
		return toResult(response, factsJson, IncidentAnalysisPrompt.VERSION);
	}

	/**
	 * 코드 맥락까지 함께 보내는 것 말고는 기본 분석과 같다.
	 * <p>
	 * 요청을 만들고 응답을 검증하는 자리를 나누지 않는다. 두 분석은 같은 출력 구조를 요구하므로 응답을
	 * 읽는 규칙도 같고, 그것을 복사해 두면 한쪽만 고쳐졌을 때 두 분석이 서로 다른 기준으로 저장된다.
	 * 실제로 다른 것은 지시와 사용자 message와 판 번호, 이 셋뿐이며 그래서 그 셋만 인자로 흐른다.
	 */
	@Override
	public IncidentAnalysisResult analyzeWithCode(IncidentAnalysisFacts facts, GitHubCodeContext code) {
		String factsJson = IncidentAnalysisPrompt.factsJson(facts);
		String contextJson = IncidentCodeAnalysisPrompt.contextJson(code);
		ClaudeMessageResponse response = call(request(IncidentCodeAnalysisPrompt.SYSTEM_INSTRUCTION,
				IncidentCodeAnalysisPrompt.userMessage(factsJson, contextJson)));
		return toResult(response, factsJson, IncidentCodeAnalysisPrompt.VERSION);
	}

	private ClaudeMessageRequest request(String systemInstruction, String userMessage) {
		return new ClaudeMessageRequest(this.model, MAX_OUTPUT_TOKENS, systemInstruction,
				List.of(new ClaudeMessageRequest.Message(USER_ROLE, userMessage)),
				new ClaudeMessageRequest.OutputConfig(new ClaudeMessageRequest.Format(
						ClaudeMessageRequest.Format.JSON_SCHEMA, IncidentAnalysisPrompt.outputSchema())));
	}

	/**
	 * 요청을 보내고 응답 본문을 읽는다.
	 * <p>
	 * 상태 코드를 직접 다룬다. 기본 동작에 맡기면 4xx와 5xx에서 예외가 나는데 그 예외의 message에는
	 * 응답 본문이 담겨 있고, 그것이 log나 상위 예외로 흘러가면 내보내지 않기로 한 값이 남는다. 여기서
	 * 가로채면 본문을 한 번도 읽지 않은 채로 실패를 만들 수 있다.
	 * <p>
	 * 429도 같은 실패로 다룬다. 잠시 뒤 다시 부르면 될 상황이지만, 여기서 기다렸다 다시 부르면 이미
	 * 한도를 넘긴 곳에 요청을 더 보내는 셈이 된다. 다시 시도할지는 사람이 정한다.
	 */
	private ClaudeMessageResponse call(ClaudeMessageRequest request) {
		try {
			ClaudeMessageResponse response = this.restClient.post()
				.uri(MESSAGES_PATH)
				.body(request)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (failedRequest, failedResponse) -> {
					int status = failedResponse.getStatusCode().value();
					logger.warn("Claude API가 오류로 응답했다. status={} providerRequestId={}", status,
							failedResponse.getHeaders().getFirst(REQUEST_ID_HEADER));
					throw new IncidentAnalysisUnavailableException("provider_http_" + status, null);
				})
				.body(ClaudeMessageResponse.class);
			if (response == null) {
				throw new IncidentAnalysisUnavailableException("provider_empty_body", null);
			}
			return response;
		}
		catch (ResourceAccessException ex) {
			// 연결하지 못했거나 제한 시간 안에 응답이 오지 않았다. 여기까지는 provider가 무엇을 했는지
			// 알 수 없으므로, 요청이 실제로 처리되었을 가능성도 있다고 보고 재시도하지 않는다.
			logger.warn("Claude API에 닿지 못했다. reason={}", ex.getClass().getSimpleName());
			throw new IncidentAnalysisUnavailableException("provider_unreachable", ex);
		}
		catch (RestClientException ex) {
			logger.warn("Claude API 호출에 실패했다. reason={}", ex.getClass().getSimpleName());
			throw new IncidentAnalysisUnavailableException("provider_call_failed", ex);
		}
	}

	/**
	 * 응답을 저장할 수 있는 분석으로 옮긴다. 하나라도 어긋나면 분석을 만들지 않는다.
	 * <p>
	 * 끝까지 만들어진 응답만 받는다. 길이 제한에 걸려 잘렸거나 거절된 응답도 HTTP로는 성공이고 JSON
	 * 구조도 갖추고 있을 수 있는데, 그것을 저장하면 문장이 중간에서 끊긴 분석이 정상 결과로 남는다.
	 * <p>
	 * 본문 조각을 종류로 고른다. 응답에는 분석 본문 말고 다른 종류의 조각이 함께 올 수 있어 첫 조각을
	 * 무조건 읽으면 안 되고, 본문 조각이 정확히 하나가 아니면 어느 것이 분석인지 우리가 정할 근거가 없다.
	 * <p>
	 * model은 설정값이 아니라 응답이 알려 준 값을 쓴다. 설정에는 alias가 들어갈 수 있어, 나중에 어떤
	 * model의 결과인지 물으면 설정값은 답하지 못한다.
	 */
	private IncidentAnalysisResult toResult(ClaudeMessageResponse response, String factsJson, String promptVersion) {
		if (!ClaudeMessageResponse.STOP_REASON_END_TURN.equals(response.stopReason())) {
			throw new IncidentAnalysisUnavailableException("provider_incomplete_response", null);
		}
		ClaudeAnalysisPayload payload = readPayload(analysisText(response));
		try {
			return new IncidentAnalysisResult(payload.summary(), payload.primaryHypothesis(),
					confidence(payload.confidence()), payload.evidence(), payload.recommendedActions(),
					payload.limitations(), response.model(), promptVersion, factsJson);
		}
		catch (IllegalArgumentException ex) {
			logger.warn("Claude 분석이 계약을 지키지 않았다. reason={}", ex.getMessage());
			throw new IncidentAnalysisUnavailableException("provider_invalid_analysis", ex);
		}
	}

	private static String analysisText(ClaudeMessageResponse response) {
		List<ClaudeMessageResponse.ContentBlock> blocks = (response.content() != null) ? response.content() : List.of();
		List<String> texts = blocks.stream()
			.filter((block) -> ClaudeMessageResponse.ContentBlock.TYPE_TEXT.equals(block.type()))
			.map(ClaudeMessageResponse.ContentBlock::text)
			.filter((text) -> text != null && !text.isBlank())
			.toList();
		if (texts.size() != 1) {
			throw new IncidentAnalysisUnavailableException("provider_unexpected_content", null);
		}
		return texts.get(0);
	}

	private static ClaudeAnalysisPayload readPayload(String analysisText) {
		try {
			return ANALYSIS_MAPPER.readValue(analysisText, ClaudeAnalysisPayload.class);
		}
		catch (JacksonException ex) {
			// 읽지 못한 본문을 log에 남기지 않는다. 그 안에는 model이 무엇을 만들었든 담겨 있고,
			// 무엇이 잘못됐는지는 예외의 Type만으로도 충분히 좁혀진다.
			logger.warn("Claude 분석 본문을 읽지 못했다. reason={}", ex.getClass().getSimpleName());
			throw new IncidentAnalysisUnavailableException("provider_unparsable_analysis", ex);
		}
	}

	private static AnalysisConfidence confidence(String value) {
		try {
			return AnalysisConfidence.valueOf(value);
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw new IncidentAnalysisUnavailableException("provider_unknown_confidence", ex);
		}
	}

}

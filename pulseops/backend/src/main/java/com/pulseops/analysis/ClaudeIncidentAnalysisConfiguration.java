package com.pulseops.analysis;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Claude 연동에 필요한 것들을 만든다.
 * <p>
 * 기능이 꺼져 있으면 {@link IncidentAnalysisClient} 자체를 만들지 않는다. 껍데기 Client를 만들어 두고
 * 안에서 거절하는 방법도 있지만, 그러면 credential이 없는 환경에서도 client가 존재하게 되어 "기능이
 * 꺼져 있다"는 사실이 Application의 구조가 아니라 어느 method 안의 분기가 된다. 없으면 없는 것이
 * 분명하고, {@link IncidentAiAnalysisService}는 그 없음을 그대로 읽는다.
 * <p>
 * 켜져 있는데 설정이 비어 있으면 기동에서 실패한다. 그 상태로 뜨면 운영자는 기능이 켜졌다고 믿는데
 * 실제로는 분석을 요청할 때마다 실패하고, 그것을 알아채는 시점은 대개 장애를 분석하려던 순간이다.
 * 설정이 잘못됐다는 사실은 배포할 때 드러나야 한다. 꺼져 있을 때는 아무것도 확인하지 않으므로 기본
 * 실행과 전체 테스트에는 credential이 필요 없다.
 * <p>
 * {@code RestClient.Builder} bean을 주입받지 않고 여기서 만든다. 이 Application에는 그 bean을 만드는
 * 자동 구성이 없고, 외부 API를 부르는 자리도 여기 하나뿐이라 공용 builder가 있어야 할 이유가 없다.
 * 오히려 공용이면 나중에 다른 곳에서 붙인 설정이 이 호출의 header나 timeout을 바꾼다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClaudeAnalysisProperties.class)
public class ClaudeIncidentAnalysisConfiguration {

	/** Messages API의 공식 주소이다. 설정으로 열지 않는다. 바꿔 부를 다른 주소가 없다. */
	private static final String BASE_URL = "https://api.anthropic.com";

	/** 인증 header 이름이다. 값은 설정에서만 오며 어떤 log에도 남기지 않는다. */
	private static final String API_KEY_HEADER = "x-api-key";

	private static final String VERSION_HEADER = "anthropic-version";

	/** Messages API의 계약 판이다. 이 값이 응답의 모양을 정하므로 코드가 아는 판을 명시한다. */
	private static final String API_VERSION = "2023-06-01";

	/**
	 * 연결에 이만큼 걸리면 포기한다. 연결이 늦는 것은 상대가 생각 중이어서가 아니라 닿지 못한다는
	 * 뜻이므로 오래 기다릴 이유가 없다.
	 */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * 응답을 이만큼 기다린다.
	 * <p>
	 * 넉넉한 값이다. 요청 하나가 답을 만들기 전에 스스로 생각하는 단계를 거치므로 수십 초가 걸릴 수
	 * 있고, 그 시간을 기다리지 못하면 정상적인 분석이 timeout으로 실패한다. 대신 무한정 기다리지는
	 * 않는다. 이 호출은 사람이 버튼을 누르고 기다리는 요청이라, 언젠가는 실패로 답해야 한다.
	 */
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

	/**
	 * 기능이 켜져 있을 때만 만든다. 설정에 {@code enabled=true}가 명시된 경우이며, 값이 없으면 만들지
	 * 않는다. 기본이 켜짐이면 credential이 없는 환경에서 기동이 실패하거나 의도하지 않은 요청이 나간다.
	 */
	@Bean
	@ConditionalOnProperty(prefix = "pulseops.ai.claude", name = "enabled", havingValue = "true")
	IncidentAnalysisClient claudeIncidentAnalysisClient(ClaudeAnalysisProperties properties) {
		return new ClaudeIncidentAnalysisClient(restClient(requireConfigured(properties.apiKey(), "api-key")),
				requireConfigured(properties.model(), "model"));
	}

	private static RestClient restClient(String apiKey) {
		return RestClient.builder()
			.baseUrl(BASE_URL)
			.requestFactory(requestFactory())
			.defaultHeader(API_KEY_HEADER, apiKey)
			.defaultHeader(VERSION_HEADER, API_VERSION)
			.build();
	}

	private static JdkClientHttpRequestFactory requestFactory() {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
		requestFactory.setReadTimeout(READ_TIMEOUT);
		return requestFactory;
	}

	/**
	 * 값이 무엇인지는 message에 담지 않는다. 여기서 걸리는 값 하나가 API key이고, 그 값이 기동 실패
	 * message에 담기면 그대로 배포 log에 남는다. 어느 설정이 비었는지만 알려 준다.
	 */
	private static String requireConfigured(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(
					"pulseops.ai.claude.enabled=true인데 pulseops.ai.claude." + name + "이(가) 비어 있다");
		}
		return value;
	}

}

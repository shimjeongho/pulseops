package com.pulseops.github;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * GitHub 연동에 필요한 것들을 만든다.
 * <p>
 * 기능이 꺼져 있으면 {@link IncidentGitHubContextClient} 자체를 만들지 않는다. 껍데기 Client를 만들어
 * 두고 안에서 거절하는 방법도 있지만, 그러면 credential이 없는 환경에서도 client가 존재하게 되어
 * "기능이 꺼져 있다"는 사실이 Application의 구조가 아니라 어느 method 안의 분기가 된다.
 * <p>
 * 켜져 있는데 설정이 비어 있으면 기동에서 실패한다. 그 상태로 뜨면 운영자는 기능이 켜졌다고 믿는데
 * 실제로는 수집할 때마다 실패하고, 그것을 알아채는 시점은 대개 장애를 분석하려던 순간이다. 꺼져 있을
 * 때는 아무것도 확인하지 않으므로 기본 실행과 전체 테스트에 credential이 필요 없다.
 * <p>
 * Claude 쪽과 {@code RestClient.Builder}를 공유하지 않는다. 두 호출은 baseUrl도 인증 header도 기다리는
 * 시간도 다르며, 공용 builder를 두면 한쪽에 붙인 설정이 다른 쪽 요청의 header를 바꾼다. 무엇보다 인증
 * header를 공유하는 builder는 한쪽의 credential이 다른 쪽 요청에 실려 나갈 수 있는 구조이다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubProperties.class)
public class GitHubIncidentContextConfiguration {

	/** GitHub REST API의 공식 주소이다. 설정으로 열지 않는다. 바꿔 부를 다른 주소가 없다. */
	private static final String BASE_URL = "https://api.github.com";

	/** 인증 header 이름이다. 값은 설정에서만 오며 어떤 log에도 남기지 않는다. */
	private static final String AUTHORIZATION_HEADER = "Authorization";

	/** 공식 문서가 정한 token 표기이다. */
	private static final String BEARER_PREFIX = "Bearer ";

	private static final String ACCEPT_HEADER = "Accept";

	/** 공식 문서가 REST API에 권장하는 media type이다. */
	private static final String ACCEPT = "application/vnd.github+json";

	private static final String VERSION_HEADER = "X-GitHub-Api-Version";

	/**
	 * REST API의 계약 판이다.
	 * <p>
	 * 명시하지 않으면 GitHub이 오래된 기본 판으로 처리하므로, 코드가 아는 판을 직접 적는다. 지원이 끝난
	 * 판을 보내면 410으로 거절되는데, 그렇게 드러나는 편이 응답 모양이 조용히 달라지는 것보다 낫다.
	 */
	private static final String API_VERSION = "2026-03-10";

	/**
	 * 연결에 이만큼 걸리면 포기한다. 연결이 늦는 것은 상대가 생각 중이어서가 아니라 닿지 못한다는
	 * 뜻이므로 오래 기다릴 이유가 없다.
	 */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * 응답을 이만큼 기다린다.
	 * <p>
	 * Claude 호출보다 짧다. 그쪽은 model이 답을 만드는 시간을 기다리지만 이쪽은 조회이고, 맥락 하나에
	 * 여러 번 부르므로 한 번이 오래 걸리면 사람이 기다리는 시간이 그만큼 곱해진다.
	 */
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

	/**
	 * 기능이 켜져 있을 때만 만든다. 설정에 {@code enabled=true}가 명시된 경우이며, 값이 없으면 만들지
	 * 않는다. 기본이 켜짐이면 credential이 없는 환경에서 기동이 실패하거나 의도하지 않은 요청이 나간다.
	 */
	@Bean
	@ConditionalOnProperty(prefix = "pulseops.github", name = "enabled", havingValue = "true")
	IncidentGitHubContextClient gitHubIncidentContextClient(GitHubProperties properties) {
		requireConfigured(properties.owner(), "owner");
		requireConfigured(properties.repository(), "repository");
		requireConfigured(properties.ref(), "ref");
		requireConfigured(properties.sourceRoot(), "source-root");
		return new GitHubRestIncidentContextClient(restClient(requireConfigured(properties.token(), "token")),
				properties);
	}

	private static RestClient restClient(String token) {
		return RestClient.builder()
			.baseUrl(BASE_URL)
			.requestFactory(requestFactory())
			.defaultHeader(AUTHORIZATION_HEADER, BEARER_PREFIX + token)
			.defaultHeader(ACCEPT_HEADER, ACCEPT)
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
	 * 값이 무엇인지는 message에 담지 않는다. 여기서 걸리는 값 하나가 token이고, 그 값이 기동 실패
	 * message에 담기면 그대로 배포 log에 남는다. 어느 설정이 비었는지만 알려 준다.
	 */
	private static String requireConfigured(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("pulseops.github.enabled=true인데 pulseops.github." + name + "이(가) 비어 있다");
		}
		return value;
	}

}

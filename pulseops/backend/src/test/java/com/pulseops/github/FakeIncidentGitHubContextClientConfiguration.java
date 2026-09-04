package com.pulseops.github;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 테스트 Context에 가짜 GitHub Client를 세운다.
 * <p>
 * Production 설정은 건드리지 않는다. {@code pulseops.github.enabled}는 기본값인 꺼짐 그대로이고, 그래서
 * 진짜 GitHub Client는 만들어지지 않는다. 이 bean이 그 자리를 대신하므로 테스트에 token이 필요 없고,
 * 기능을 켜는 설정과 이 bean이 서로 얽히지도 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeIncidentGitHubContextClientConfiguration {

	@Bean
	FakeIncidentGitHubContextClient fakeIncidentGitHubContextClient() {
		return new FakeIncidentGitHubContextClient();
	}

}

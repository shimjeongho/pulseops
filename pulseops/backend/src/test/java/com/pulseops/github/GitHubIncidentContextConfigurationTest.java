package com.pulseops.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 설정에 따라 무엇이 만들어지고 무엇이 실패하는지 고정한다.
 * <p>
 * Application 전체를 띄우지 않는다. 여기서 볼 것은 설정 하나가 bean 하나의 존재를 어떻게 바꾸는지이며,
 * 그것을 확인하려고 MySQL과 Kafka까지 세우면 설정마다 Context를 새로 만들어야 해 확인 자체가 비싸진다.
 */
class GitHubIncidentContextConfigurationTest {

	private static final String DUMMY_TOKEN = "ghp_dummy_not_a_real_token";

	private static final String[] COMPLETE_CONFIGURATION = { "pulseops.github.enabled=true",
			"pulseops.github.token=" + DUMMY_TOKEN, "pulseops.github.owner=pulseops-test",
			"pulseops.github.repository=pulseops", "pulseops.github.ref=main",
			"pulseops.github.source-root=demo-shop/backend/src/main/java" };

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of())
		.withUserConfiguration(GitHubIncidentContextConfiguration.class);

	/**
	 * 기본은 꺼짐이다. 설정을 아무것도 하지 않은 환경에서 남의 repository로 나가는 요청이 준비되어 있으면
	 * 안 된다.
	 */
	@Test
	void createsNothingWhenTheFeatureIsNotEnabled() {
		this.contextRunner
			.run((context) -> assertThat(context).hasNotFailed().doesNotHaveBean(IncidentGitHubContextClient.class));
	}

	@Test
	void createsTheClientWhenEverythingIsConfigured() {
		this.contextRunner.withPropertyValues(COMPLETE_CONFIGURATION)
			.run((context) -> assertThat(context).hasSingleBean(IncidentGitHubContextClient.class));
	}

	/**
	 * 켜져 있는데 설정이 하나라도 비어 있으면 기동에서 실패한다.
	 * <p>
	 * 그대로 뜨면 운영자는 기능이 켜졌다고 믿는데 실제로는 수집할 때마다 실패하고, 그것을 알아채는 시점은
	 * 대개 장애를 분석하려던 순간이다. source root가 특히 그렇다. 그 값이 비어 있으면 검색을 좁히는 1차
	 * 안전 경계가 사라지므로, 비어 있는 채로 동작하게 두어서는 안 된다.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "token", "owner", "repository", "ref", "source-root" })
	void failsToStartWhenEnabledWithoutTheSettingsItNeeds(String missing) {
		this.contextRunner.withPropertyValues(withoutSetting(missing))
			.run((context) -> assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining(missing));
	}

	/** 기동 실패 message에 token이 담기면 그대로 배포 log에 남는다. */
	@Test
	void keepsTheTokenOutOfTheStartupFailure() {
		this.contextRunner.withPropertyValues(withoutSetting("owner"))
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageNotContaining(DUMMY_TOKEN));
	}

	private static String[] withoutSetting(String name) {
		return Arrays.stream(COMPLETE_CONFIGURATION)
			.filter((property) -> !property.startsWith("pulseops.github." + name + "="))
			.toArray(String[]::new);
	}

}

package com.pulseops.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 설정에 따라 무엇이 만들어지고 무엇이 실패하는지 고정한다.
 * <p>
 * Application 전체를 띄우지 않는다. 여기서 볼 것은 설정 하나가 bean 하나의 존재를 어떻게 바꾸는지이며,
 * 그것을 확인하려고 MySQL과 Kafka까지 세우면 설정마다 Context를 새로 만들어야 해 확인 자체가 비싸진다.
 */
class ClaudeIncidentAnalysisConfigurationTest {

	private static final String DUMMY_API_KEY = "sk-ant-dummy-not-a-real-key";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of())
		.withUserConfiguration(ClaudeIncidentAnalysisConfiguration.class);

	/** 기본은 꺼짐이다. 설정을 아무것도 하지 않은 환경에서 외부로 나가는 요청이 준비되어 있으면 안 된다. */
	@Test
	void createsNothingWhenTheFeatureIsNotEnabled() {
		this.contextRunner
			.run((context) -> assertThat(context).hasNotFailed().doesNotHaveBean(IncidentAnalysisClient.class));
	}

	@Test
	void createsTheClientWhenEverythingIsConfigured() {
		this.contextRunner
			.withPropertyValues("pulseops.ai.claude.enabled=true", "pulseops.ai.claude.api-key=" + DUMMY_API_KEY,
					"pulseops.ai.claude.model=claude-opus-5")
			.run((context) -> assertThat(context).hasSingleBean(IncidentAnalysisClient.class));
	}

	/**
	 * 켜져 있는데 설정이 비어 있으면 기동에서 실패한다.
	 * <p>
	 * 그대로 뜨면 운영자는 기능이 켜졌다고 믿는데 실제로는 분석을 요청할 때마다 실패하고, 그것을 알아채는
	 * 시점은 대개 장애를 분석하려던 순간이다. 설정이 잘못됐다는 사실은 배포할 때 드러나야 한다.
	 */
	@Test
	void failsToStartWhenEnabledWithoutAnApiKey() {
		this.contextRunner
			.withPropertyValues("pulseops.ai.claude.enabled=true", "pulseops.ai.claude.model=claude-opus-5")
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("api-key"));
	}

	@Test
	void failsToStartWhenEnabledWithoutAModel() {
		this.contextRunner
			.withPropertyValues("pulseops.ai.claude.enabled=true", "pulseops.ai.claude.api-key=" + DUMMY_API_KEY)
			.run((context) -> assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("model"));
	}

	/** 기동 실패 message에 API key가 담기면 그대로 배포 log에 남는다. */
	@Test
	void keepsTheApiKeyOutOfTheStartupFailure() {
		this.contextRunner
			.withPropertyValues("pulseops.ai.claude.enabled=true", "pulseops.ai.claude.api-key=" + DUMMY_API_KEY)
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageNotContaining(DUMMY_API_KEY));
	}

}

package com.pulseops.demoshop.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.ProducerListener;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Telemetry 구성 요소의 등록 조건과 Filter 실행 순서를 검증한다. 순서가 뒤집히면 TelemetryFilter가
 * requestId를 읽지 못하고, 관측 스위치가 동작하지 않으면 문제가 생겼을 때 관측만 끌 수 없다.
 */
class TelemetryConfigurationTest {

	private static final String REQUEST_ID_REGISTRATION = "requestIdFilterRegistration";

	private static final String TELEMETRY_REGISTRATION = "telemetryFilterRegistration";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
		.withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
		.withUserConfiguration(TelemetryConfiguration.class);

	@Test
	void registersTheObservationComponentsByDefault() {
		this.contextRunner.run((context) -> {
			assertThat(context).hasSingleBean(TelemetryPublisher.class);
			assertThat(registrationOf(context, REQUEST_ID_REGISTRATION).getFilter())
				.isInstanceOf(RequestIdFilter.class);
			assertThat(registrationOf(context, TELEMETRY_REGISTRATION).getFilter())
				.isInstanceOf(TelemetryFilter.class);
		});
	}

	@Test
	void runsRequestIdFilterBeforeTelemetryFilter() {
		this.contextRunner.run((context) -> assertThat(registrationOf(context, REQUEST_ID_REGISTRATION).getOrder())
			.isLessThan(registrationOf(context, TELEMETRY_REGISTRATION).getOrder()));
	}

	/**
	 * Topic을 Broker의 auto creation에 맡기지 않는다는 결정을 고정한다. Broker 기본 partition
	 * 수가 우리가 정한 값과 다르면 endpoint별 집계 단위가 조용히 달라진다.
	 */
	@Test
	void declaresTheTelemetryTopicWithThreePartitionsAndOneReplica() {
		this.contextRunner.run((context) -> {
			NewTopic topic = context.getBean(NewTopic.class);

			assertThat(topic.name()).isEqualTo("demo-shop.telemetry.api.v1");
			assertThat(topic.numPartitions()).isEqualTo(3);
			assertThat(topic.replicationFactor()).isEqualTo((short) 1);
		});
	}

	/**
	 * 관측을 끄면 Filter뿐 아니라 Kafka와 닿는 구성까지 함께 빠져야 한다. Topic Bean이 남아 있으면
	 * Broker가 없는 환경에서 기동할 때 불필요하게 Broker 연결을 시도한다. Request 식별자 부여는
	 * Kafka와 무관하므로 그대로 남는다.
	 */
	@Test
	void removesEveryKafkaFacingComponentWhenTelemetryIsDisabled() {
		this.contextRunner.withPropertyValues("pulseops.telemetry.enabled=false").run((context) -> {
			assertThat(context).doesNotHaveBean(TELEMETRY_REGISTRATION);
			assertThat(context).doesNotHaveBean(TelemetryPublisher.class);
			assertThat(context).doesNotHaveBean(NewTopic.class);
			assertThat(context).doesNotHaveBean(ProducerListener.class);
			assertThat(context).hasBean(REQUEST_ID_REGISTRATION);
		});
	}

	/**
	 * 전송 실패 log는 payload를 남기지 않는 TelemetryPublisher 쪽 하나로 통일한다. Spring Kafka
	 * 기본 Listener는 payload를 함께 남기므로 사용하지 않는다.
	 */
	@Test
	void replacesTheFrameworkProducerListenerThatLogsThePayload() {
		this.contextRunner.run((context) -> assertThat(context.getBean(ProducerListener.class))
			.isNotInstanceOf(LoggingProducerListener.class));
	}

	@Test
	void bindsTelemetryPropertiesWithUsableDefaults() {
		this.contextRunner.run((context) -> {
			TelemetryProperties properties = context.getBean(TelemetryProperties.class);

			assertThat(properties.enabled()).isTrue();
			assertThat(properties.serviceInstance()).isNotBlank();
		});
	}

	@Test
	void bindsTheConfiguredServiceInstance() {
		this.contextRunner.withPropertyValues("pulseops.telemetry.service-instance=demo-shop-02")
			.run((context) -> assertThat(context.getBean(TelemetryProperties.class).serviceInstance())
				.isEqualTo("demo-shop-02"));
	}

	private FilterRegistrationBean<?> registrationOf(ApplicationContext context, String beanName) {
		return context.getBean(beanName, FilterRegistrationBean.class);
	}

}

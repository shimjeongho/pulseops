package com.pulseops.demoshop.telemetry;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.servlet.filter.OrderedFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.ProducerListener;
import tools.jackson.databind.ObjectMapper;

/**
 * Telemetry 관측 구성 요소를 등록한다. Filter를 Component Scan에 맡기지 않고 여기서만 등록하여
 * 실행 순서와 활성화 조건을 한 곳에서 결정한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelemetryProperties.class)
public class TelemetryConfiguration {

	/**
	 * Request/Response를 감싸는 Framework Filter 뒤에서 동작해야 하므로
	 * {@link OrderedFilter#REQUEST_WRAPPER_FILTER_MAX_ORDER} 다음 구간을 사용한다.
	 */
	private static final int REQUEST_ID_FILTER_ORDER = OrderedFilter.REQUEST_WRAPPER_FILTER_MAX_ORDER + 10;

	/**
	 * RequestIdFilter가 식별자를 확정한 뒤에 실행되어야 Event에 실을 requestId를 항상 읽을 수
	 * 있다. 그래서 반드시 RequestIdFilter보다 뒤 순서를 갖는다.
	 */
	private static final int TELEMETRY_FILTER_ORDER = REQUEST_ID_FILTER_ORDER + 10;

	/**
	 * Request 식별자 부여는 외부 시스템에 의존하지 않고 Client 추적에도 쓰이므로 관측 스위치와
	 * 무관하게 항상 동작한다.
	 */
	@Bean
	FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
		FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
		registration.setOrder(REQUEST_ID_FILTER_ORDER);
		return registration;
	}

	/**
	 * 관측을 켰을 때만 필요한 구성이다. 하나의 조건으로 묶어 두면 enabled=false에서 Filter와
	 * Topic 준비가 함께 빠지므로, Broker가 없는 환경에서 Demo Shop이 Kafka에 접근할 여지가 아예
	 * 없어진다.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "pulseops.telemetry", name = "enabled", matchIfMissing = true)
	static class TelemetryObservationConfiguration {

		/** Phase 2에서 확정한 partition 수이다. 집계 순서 보장 단위를 정하므로 임의로 바꾸지 않는다. */
		private static final int TOPIC_PARTITIONS = 3;

		/** 단일 Broker 개발 환경 기준이다. */
		private static final short TOPIC_REPLICAS = 1;

		@Bean
		TelemetryPublisher telemetryPublisher(KafkaTemplate<String, String> kafkaTemplate,
				ObjectMapper objectMapper) {
			return new TelemetryPublisher(kafkaTemplate, objectMapper);
		}

		@Bean
		FilterRegistrationBean<TelemetryFilter> telemetryFilterRegistration(TelemetryPublisher telemetryPublisher,
				TelemetryProperties telemetryProperties) {
			FilterRegistrationBean<TelemetryFilter> registration = new FilterRegistrationBean<>(
					new TelemetryFilter(telemetryPublisher, telemetryProperties));
			registration.setOrder(TELEMETRY_FILTER_ORDER);
			return registration;
		}

		/**
		 * Spring Kafka 기본 ProducerListener는 전송 실패 시 Message payload를 log에 함께 남긴다.
		 * Event가 확장되면 그대로 노출 경로가 되므로 쓰지 않고, 실패 기록은 payload 없이 eventId만
		 * 남기는 TelemetryPublisher의 log 하나로 통일한다.
		 */
		@Bean
		ProducerListener<Object, Object> telemetryProducerListener() {
			return new ProducerListener<>() {
			};
		}

		/**
		 * Topic을 Broker의 auto creation에 맡기지 않는다. Broker 기본 partition 수가 우리가 정한
		 * 값과 다르면 endpoint별 집계 단위가 조용히 달라지기 때문이다.
		 */
		@Bean
		NewTopic telemetryTopic() {
			return TopicBuilder.name(TelemetryPublisher.TOPIC)
				.partitions(TOPIC_PARTITIONS)
				.replicas(TOPIC_REPLICAS)
				.build();
		}

	}

}

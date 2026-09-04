package com.pulseops.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Consumer Lag을 읽는 데 필요한 것을 만든다.
 * <p>
 * Listener를 띄우지 않는 구성에서는 아무것도 만들지 않는다. 그런 구성은 이 Application이 Kafka에 붙지
 * 않는다는 뜻이고, Broker가 없는 환경에서 Admin Client를 만들어 두면 그 Client의 Thread가 닿지 않는
 * 주소로 연결을 계속 시도한다. {@code TelemetryConsumerConfiguration}이 DLT Topic 선언을 같은 조건으로
 * 감추는 이유와 같다.
 * <p>
 * 그 구성에서 lag을 물으면 {@link ConsumerLagService}가 "지금은 답할 수 없다"로 답한다. Client가 없는데
 * 있는 척하는 껍데기를 두지 않는 것은, 그러면 "지금 lag을 읽을 수 있는가"가 Application의 구성이 아니라
 * 어느 method 안의 분기가 되기 때문이다.
 * <p>
 * 연결 설정을 여기서 다시 적지 않고 Spring Boot가 이미 만들어 둔 {@link KafkaAdmin}의 것을 그대로 쓴다.
 * 같은 Broker를 두 벌의 설정으로 가리키면 한쪽만 바뀌었을 때 Consumer가 읽는 Cluster와 lag을 세는
 * Cluster가 달라진다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.kafka.listener", name = "auto-startup", matchIfMissing = true)
public class ConsumerLagConfiguration {

	/**
	 * Application이 살아 있는 동안 하나만 두고 함께 쓴다. Spring이 종료할 때 닫으므로 Client가 만든
	 * Thread와 연결이 남지 않는다.
	 */
	@Bean(destroyMethod = "close")
	Admin consumerLagAdmin(KafkaAdmin kafkaAdmin) {
		return Admin.create(kafkaAdmin.getConfigurationProperties());
	}

	@Bean
	KafkaConsumerLagClient kafkaAdminConsumerLagClient(Admin consumerLagAdmin) {
		return new KafkaAdminConsumerLagClient(consumerLagAdmin);
	}

}

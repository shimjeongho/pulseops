package com.pulseops.kafka;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 테스트 Context에 가짜 Consumer Lag Client를 세운다.
 * <p>
 * Production 설정은 건드리지 않는다. Listener를 띄우지 않는 Context에서는 진짜 Client가 만들어지지
 * 않으므로, 이 bean이 그 자리를 대신한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeKafkaConsumerLagClientConfiguration {

	@Bean
	FakeKafkaConsumerLagClient fakeKafkaConsumerLagClient() {
		return new FakeKafkaConsumerLagClient();
	}

}

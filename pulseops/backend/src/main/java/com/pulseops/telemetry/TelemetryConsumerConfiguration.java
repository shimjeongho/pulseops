package com.pulseops.telemetry;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Telemetry Consumer가 처리에 실패했을 때 무엇을 하는지 정한다.
 * <p>
 * Phase 6까지는 이 구성이 없었다. 처리 중 예외가 나가면 Spring Kafka 기본 error handler가 같은
 * Record를 즉시 여러 번 다시 넘기다가 log만 남기고 넘어갔고, 그 Event는 어디에도 남지 않았다. 그래서
 * Metric 계층과 판정 계층이 저장소 장애를 각자 삼키는 편이 차라리 나았다.
 * <p>
 * 이제는 실패를 올려 보내는 것이 의미를 갖는다. 잠깐의 저장소 장애는 짧은 간격을 두고 다시 시도하고,
 * 그래도 안 되면 원본 Record를 DLT에 남겨 격리한다. 어느 쪽이든 Consumer는 그 자리에 멈추지 않고 다음
 * Record로 넘어간다.
 * <p>
 * 이것은 Consumer 처리에 대한 보장일 뿐이다. Demo Shop Producer는 발행 실패 시 요청을 살리는
 * fail-open이므로, Broker에 닿지 못한 Event는 여기서 다룰 수 있는 대상이 아니다.
 */
@Configuration(proxyBeanMethods = false)
public class TelemetryConsumerConfiguration {

	/** 원본 Topic과 한눈에 이어지도록 접미사만 붙인다. 새로운 main topic version이 아니다. */
	static final String DEAD_LETTER_TOPIC = TelemetryEventListener.TOPIC + ".dlt";

	/**
	 * 최초 시도 뒤 재시도 횟수다. 이 값에 1을 더한 만큼 처리를 시도한다. 잠깐의 저장소 장애를 넘기는
	 * 것이 목적이라 길게 잡지 않는다. 오래 붙들면 그동안 같은 partition의 뒤 Record가 전부 밀린다.
	 */
	private static final long MAX_RETRIES = 2;

	/**
	 * 재시도 간격이다. 재시도는 Consumer thread를 붙잡으므로, 세 번의 시도가 합쳐도 Metric 집계 창
	 * 300초에 비하면 무시할 수 있는 수준으로 둔다. 늦게 도착한 Event가 창 밖으로 밀려나면 재시도가
	 * 오히려 Metric을 잃게 만든다.
	 */
	private static final long RETRY_BACKOFF_MILLIS = 500;

	/** 원본 partition을 그대로 쓰려면 DLT도 같은 수의 partition을 가져야 한다. */
	private static final int DEAD_LETTER_PARTITIONS = 3;

	private static final short DEAD_LETTER_REPLICAS = 1;

	/**
	 * Listener를 띄우지 않는 Context에서는 DLT도 필요하지 않다. Topic 선언을 남겨 두면 Kafka가 없는
	 * Test Context마다 Admin이 Broker 연결을 기다린다.
	 */
	@Bean
	@ConditionalOnProperty(prefix = "spring.kafka.listener", name = "auto-startup", matchIfMissing = true)
	NewTopic telemetryDeadLetterTopic() {
		return TopicBuilder.name(DEAD_LETTER_TOPIC)
			.partitions(DEAD_LETTER_PARTITIONS)
			.replicas(DEAD_LETTER_REPLICAS)
			.build();
	}

	/**
	 * 실패한 Record를 원본과 같은 partition의 DLT로 보낸다. 같은 Key의 Record가 원본에서 한 partition에
	 * 모였다면 DLT에서도 그렇게 모여야 나중에 사람이 순서대로 읽을 수 있다.
	 * <p>
	 * DLT 전송이 실패하면 recoverer가 예외를 올려 보내고, 그러면 offset이 넘어가지 않아 그 Record는
	 * 다시 전달된다. 격리에 실패한 Event를 성공으로 처리해 조용히 잃지 않기 위한 것이며, Spring Kafka의
	 * 기본 동작을 그대로 쓴다.
	 * <p>
	 * DLT를 다시 읽어 재처리하는 Consumer는 두지 않는다. DLT는 사람이 원인을 확인할 때까지 붙잡아 두는
	 * 격리 지점이고, 자동으로 되돌리면 같은 실패를 무한히 반복할 수 있다.
	 */
	@Bean
	DeadLetterPublishingRecoverer telemetryDeadLetterRecoverer(KafkaTemplate<?, ?> kafkaTemplate) {
		return new DeadLetterPublishingRecoverer(kafkaTemplate,
				(record, exception) -> new TopicPartition(DEAD_LETTER_TOPIC, record.partition()));
	}

	/**
	 * 몇 번까지 다시 시도하고 어떤 실패는 시도하지 않을지를 정한다. 어디로 보낼지는 recoverer가 알고
	 * 있어 여기서 다시 정하지 않는다.
	 */
	@Bean
	DefaultErrorHandler telemetryConsumerErrorHandler(DeadLetterPublishingRecoverer recoverer) {
		DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer,
				new FixedBackOff(RETRY_BACKOFF_MILLIS, MAX_RETRIES));
		// 계약 위반은 다시 읽어도 같은 결과라 재시도가 시간만 쓴다. 한 번 만에 DLT로 보낸다.
		errorHandler.addNotRetryableExceptions(TelemetryEventContractException.class);
		return errorHandler;
	}

}

package com.pulseops.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

/**
 * 격리에 실패한 Event가 조용히 사라지지 않는지를 Broker 없이 고정한다.
 * <p>
 * DLT 전송이 실패했는데 recoverer가 아무 일도 없었던 것처럼 끝나면, Kafka는 그 Record를 처리 완료로
 * 보고 offset을 넘긴다. 그러면 재시도로도 DLT로도 남지 않은 Event가 생긴다. Broker를 실제로 죽여서
 * 확인하기는 어렵고 불안정하므로, 전송 실패만 흉내 내어 그 순간의 계약을 여기서 못 박는다.
 */
class TelemetryDeadLetterRecovererTest {

	private static final String ORIGINAL_TOPIC = TelemetryEventListener.TOPIC;

	private static final int ORIGINAL_PARTITION = 2;

	private static final long ORIGINAL_OFFSET = 41L;

	private final KafkaTemplate<?, ?> kafkaTemplate = mock(KafkaTemplate.class);

	private final DeadLetterPublishingRecoverer recoverer = new TelemetryConsumerConfiguration()
		.telemetryDeadLetterRecoverer(this.kafkaTemplate);

	/**
	 * 원본 partition을 그대로 쓴다. 같은 Key의 Record가 원본에서 한 partition에 모였다면 DLT에서도 그
	 * 순서로 남아야 나중에 사람이 읽을 수 있다.
	 */
	@Test
	@SuppressWarnings({ "unchecked", "rawtypes" })
	void sendsToTheDeadLetterTopicKeepingTheOriginalPartition() {
		given(((KafkaTemplate) this.kafkaTemplate).send(any(ProducerRecord.class)))
			.willReturn(CompletableFuture.completedFuture(null));

		this.recoverer.accept(record(), new IllegalStateException("처리에 실패했다"));

		ArgumentCaptor<ProducerRecord> published = ArgumentCaptor.forClass(ProducerRecord.class);
		((KafkaTemplate) org.mockito.BDDMockito.then(this.kafkaTemplate).should()).send(published.capture());
		assertThat(published.getValue().topic()).isEqualTo(ORIGINAL_TOPIC + ".dlt");
		assertThat(published.getValue().partition()).isEqualTo(ORIGINAL_PARTITION);
	}

	/**
	 * 전송이 실패하면 예외가 올라가야 한다. 이 예외를 받은 error handler는 offset을 넘기지 않으므로 그
	 * Record는 다시 전달된다. 격리에 실패한 Event를 성공으로 처리하는 것보다 다시 시도하는 편이 낫다.
	 */
	@Test
	@SuppressWarnings({ "unchecked", "rawtypes" })
	void failsInsteadOfSilentlySucceedingWhenTheDeadLetterSendFails() {
		given(((KafkaTemplate) this.kafkaTemplate).send(any(ProducerRecord.class)))
			.willReturn(CompletableFuture.failedFuture(new IllegalStateException("Broker에 보내지 못했다")));

		assertThatExceptionOfType(KafkaException.class)
			.isThrownBy(() -> this.recoverer.accept(record(), new IllegalStateException("처리에 실패했다")));
	}

	private static ConsumerRecord<String, String> record() {
		return new ConsumerRecord<>(ORIGINAL_TOPIC, ORIGINAL_PARTITION, ORIGINAL_OFFSET, "demo-shop:/api/products",
				"{}");
	}

}

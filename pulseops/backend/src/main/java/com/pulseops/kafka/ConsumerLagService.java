package com.pulseops.kafka;

import com.pulseops.kafka.dto.ConsumerLagResponse;
import com.pulseops.telemetry.TelemetryEventListener;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.stereotype.Service;

/**
 * PulseOps가 Telemetry Topic을 얼마나 뒤처져 읽고 있는지를 한 번 읽어 돌려준다.
 * <p>
 * Telemetry 소비 경로와 완전히 분리된 읽기 전용 경로이다. Kafka Consumer가 Event를 처리하는 흐름은 이
 * Service를 알지 못하고, 이 Service도 그 흐름을 건드리지 않는다. Metric을 쓰지도, Incident를 열지도,
 * DetectionState를 옮기지도 않는다. Lag은 Incident 판정 조건이 아니라 운영자가 보는 관측값이다.
 * <p>
 * 값을 저장하지 않는다. 부를 때마다 Kafka에게 묻고 그 순간의 값을 그대로 돌려준다. 그래서 이 경로에는
 * Table도 Redis Key도 Scheduler도 없다. 시간에 따른 추이는 여기서 답하지 않는다.
 * <p>
 * 어떤 Topic과 group을 볼지는 서버가 정한다. 요청에서 받지 않는다. 그 자리를 열면 이 endpoint가 우리
 * 연결로 Cluster 안을 아무 곳이나 들여다보는 창구가 된다.
 */
@Service
public class ConsumerLagService {

	/**
	 * 기능이 꺼진 것이 아니라 이 구성이 Kafka에 붙지 않는다는 뜻이다. {@link ConsumerLagConfiguration}
	 * 참고.
	 */
	private final Optional<KafkaConsumerLagClient> consumerLagClient;

	/**
	 * Consumer group은 설정에서 온다. 여기에 이름을 적어 두면 설정을 바꾼 뒤 화면이 실제로 소비하지 않는
	 * group의 lag을 자신 있게 0으로 보여 준다.
	 */
	private final String consumerGroup;

	public ConsumerLagService(Optional<KafkaConsumerLagClient> consumerLagClient, KafkaProperties kafkaProperties) {
		this.consumerLagClient = consumerLagClient;
		this.consumerGroup = requireConsumerGroup(kafkaProperties);
	}

	/**
	 * 현재 시각을 읽는 자리는 이 한 곳뿐이다. 응답에 적히는 시각과 offset을 읽은 시각이 같은 순간이어야
	 * 한 응답이 하나의 장면을 뜻한다.
	 */
	public ConsumerLagResponse currentLag() {
		return currentLag(Instant.now());
	}

	ConsumerLagResponse currentLag(Instant now) {
		List<PartitionOffsets> offsets = requireClient().readOffsets(TelemetryEventListener.TOPIC, this.consumerGroup);
		return ConsumerLagResponse.from(now, TelemetryEventListener.TOPIC, this.consumerGroup, offsets);
	}

	private KafkaConsumerLagClient requireClient() {
		return this.consumerLagClient
			.orElseThrow(() -> new ConsumerLagUnavailableException("kafka-listener-disabled", null));
	}

	/**
	 * group id가 없으면 기동에서 실패한다. 그 상태로 뜨면 Consumer 자체가 Event를 읽지 못하는데, 그것을
	 * 알아채는 시점은 대개 Metric이 비어 있는 이유를 찾을 때이다. 설정이 빠졌다는 사실은 배포할 때
	 * 드러나야 한다.
	 */
	private static String requireConsumerGroup(KafkaProperties kafkaProperties) {
		String groupId = kafkaProperties.getConsumer().getGroupId();
		if (groupId == null || groupId.isBlank()) {
			throw new IllegalStateException("spring.kafka.consumer.group-id가 비어 있다");
		}
		return groupId;
	}

}

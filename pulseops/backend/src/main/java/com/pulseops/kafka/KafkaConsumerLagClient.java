package com.pulseops.kafka;

import java.util.List;

/**
 * Kafka에게 offset을 물어보는 자리이다.
 * <p>
 * 읽기만 한다. 이 경계 뒤에서 offset을 commit하거나 되돌리거나 Consumer를 멈추는 일은 일어나지 않으며,
 * Topic을 만들지도 않는다. lag 조회는 관측이고, 관측이 관측 대상을 바꾸면 그것으로 본 값은 더 이상
 * 그 시스템의 상태가 아니다.
 * <p>
 * 어느 Topic과 어느 group을 볼지는 부르는 쪽이 정한다. 그 둘은 이 Application이 실제로 쓰는 값이며
 * 요청에서 오지 않는다. 인자로 받아 두면 "정말 그 둘만 물어봤는가"를 테스트가 확인할 수 있다.
 */
public interface KafkaConsumerLagClient {

	/**
	 * Topic의 모든 partition에 대해 log end offset과 group의 committed offset을 모은다.
	 * <p>
	 * 순서는 정하지 않는다. 무엇을 먼저 보여 줄지는 응답을 만드는 쪽의 몫이다.
	 *
	 * @throws ConsumerLagUnavailableException Broker에 닿지 못했거나 정해진 시간 안에 답을 받지 못했을 때
	 */
	List<PartitionOffsets> readOffsets(String topic, String consumerGroup);

}

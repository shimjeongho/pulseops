package com.pulseops.kafka;

import com.pulseops.kafka.dto.ConsumerLagResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consumer Lag을 읽는 유일한 창구이다.
 * <p>
 * 조회만 있다. offset을 되돌리거나 Consumer를 멈추고 다시 켜는 조작은 두지 않는다. 이 화면은 Kafka
 * 운영 콘솔이 아니라 지금 얼마나 밀려 있는지를 보여 주는 자리이며, Broker 상태를 바꾸는 일을 이 경로로
 * 할 수 있게 되면 관측 화면 하나가 사고를 낼 수 있는 자리가 된다.
 * <p>
 * Topic과 group을 query로 받지 않는다. 무엇을 볼지는 서버가 알고 있고, 그것을 요청이 정하게 하면 이
 * endpoint가 Cluster 안을 들여다보는 통로가 된다.
 */
@RestController
@RequestMapping("/api/kafka")
public class ConsumerLagController {

	private final ConsumerLagService consumerLagService;

	public ConsumerLagController(ConsumerLagService consumerLagService) {
		this.consumerLagService = consumerLagService;
	}

	@GetMapping("/consumer-lag")
	public ConsumerLagResponse getConsumerLag() {
		return this.consumerLagService.currentLag();
	}

}

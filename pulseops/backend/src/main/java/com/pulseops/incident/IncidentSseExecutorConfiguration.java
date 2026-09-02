package com.pulseops.incident;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Incident 변경 알림을 실제로 내보내는 Thread를 따로 둔다.
 * <p>
 * 알림을 보내는 일이 Incident를 바꾸는 일과 같은 Thread에서 일어나면 안 된다. 확인 처리는 사람이 응답을
 * 기다리는 HTTP 요청 위에서, 판정은 Kafka Consumer 위에서 돈다. 응답하지 않는 Client 하나에 쓰는 시간이
 * 그 Thread를 붙들면, 확인 응답이 늦어지고 Consumer가 poll 주기를 넘겨 재할당을 반복한다.
 * <p>
 * Thread는 하나뿐이다. 여러 벌로 나눌 이유가 없다. 보내는 일은 열려 있는 연결 몇 개에 짧은 줄을 쓰는
 * 것이고, Incident가 바뀌는 빈도는 사람이 읽을 수 있는 정도이다. 하나로 두면 알림이 만들어진 순서대로
 * 나가는 것이 자연스럽게 따라오지만, 그것을 보장이라고 부르지는 않는다. 알림 자체가 유실될 수 있고
 * 받는 쪽은 어느 알림을 받든 다시 조회하므로 순서에 기대는 곳이 없다.
 * <p>
 * 대기열은 유한하다. 무한한 대기열은 Client가 느릴 때 알림을 버리는 대신 memory에 쌓다가 Application을
 * 통째로 죽인다. 알림 하나를 잃는 것과 관측 Application이 죽는 것은 비교할 무게가 아니다.
 * <p>
 * 대기열이 가득 차면 거절한다. 부른 쪽이 대신 실행하는 방식은 쓰지 않는다. 그렇게 하면 대기열이 찼을
 * 때 하필 확인 처리 Thread나 Kafka Consumer Thread가 SSE 전송을 떠맡아, Thread를 나눈 이유가 그대로
 * 사라진다. 거절을 어떻게 다룰지는 {@link IncidentChangedEventListener}가 정한다.
 * <p>
 * 이 Bean이 생기면 Spring Boot가 자동으로 만들던 {@code applicationTaskExecutor}는 만들어지지 않는다.
 * Application이 Executor를 직접 정의하면 그것을 쓰라는 것이 Boot의 규칙이기 때문이다. 지금 그 Bean을
 * 쓰는 곳은 없다. SSE 응답은 Callable이 아니라 Deferred 방식으로 처리되어 Spring MVC의 Task Executor를
 * 지나지 않고, 이 Application에는 {@code @Async}도 없다.
 */
@Configuration(proxyBeanMethods = false)
public class IncidentSseExecutorConfiguration {

	/**
	 * 대기열에 이만큼까지 쌓아 둔다. Incident가 짧은 사이에 몰려 열리고 닫히는 구간을 넘길 만큼은
	 * 되면서, 넘치면 곧바로 알 수 있을 만큼 작은 값이다.
	 * <p>
	 * 이 값과 아래의 크기를 감춰 두지 않는 것은 대기열이 가득 찬 상황을 실제로 만들어 보는 테스트가
	 * 몇 개를 넣어야 하는지 알아야 하기 때문이다. 테스트가 같은 수를 따로 적으면 설정을 바꿀 때 한쪽만
	 * 바뀐다.
	 */
	static final int QUEUE_CAPACITY = 100;

	static final int POOL_SIZE = 1;

	@Bean
	ThreadPoolTaskExecutor incidentSseExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(POOL_SIZE);
		executor.setMaxPoolSize(POOL_SIZE);
		executor.setQueueCapacity(QUEUE_CAPACITY);
		executor.setThreadNamePrefix("incident-sse-");
		// 기본값과 같지만 명시한다. 어떤 정책을 쓰는가가 이 Executor의 계약이라 기본값에 기대면
		// 그 계약이 코드에 남지 않는다.
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		return executor;
	}

}

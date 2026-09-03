package com.pulseops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 시간 기반 실행을 켠다. Daily Incident Report가 하루에 한 번 돌아야 하기 때문이며, 그것이 이
 * Application에서 시각에 묶여 일어나야 하는 유일한 일이다.
 * <p>
 * 이상 탐지는 여기에 들어오지 않는다. 그것은 Kafka Event가 도착할 때 일어나며, 주기 실행으로 옮기면
 * 장애를 알아채기까지 걸리는 시간이 Event의 도착이 아니라 Scheduler 주기에 묶인다.
 * <p>
 * SSE 연결 신호도 이 Scheduler를 쓰지 않는다. 그쪽은 자기 Thread 하나에서 20초마다 도는 연결 유지
 * 신호라, Report 집계와 같은 Scheduler를 나눠 쓰면 집계가 오래 걸리는 날 신호가 함께 밀린다.
 */
@EnableScheduling
@SpringBootApplication
public class PulseOpsBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(PulseOpsBackendApplication.class, args);
	}

}

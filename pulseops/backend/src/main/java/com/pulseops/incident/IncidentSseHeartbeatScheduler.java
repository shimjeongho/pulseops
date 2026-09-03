package com.pulseops.incident;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 열려 있는 SSE 연결에 살아 있다는 신호를 일정한 간격으로 내보낸다.
 * <p>
 * 이 Class가 있는 이유는 Client가 스스로 알아낼 수 없는 끊김이 있기 때문이다. Backend Process가
 * 사라지면 연결을 끊어 줄 Stream 시간 초과도 그 Process와 함께 사라진다. 그때 중간의 Proxy가 Client
 * 쪽 연결을 그대로 붙들고 있으면 Client에게는 오류도 종료도 도착하지 않고, EventSource는 열린 채로
 * 남아 스스로 다시 잇지 않는다. 화면은 정상처럼 보이면서 낡은 상태에 갇힌다. 주기적으로 무엇인가
 * 보내기로 약속해 두면 그것이 오지 않는 것 자체가 Client가 판단할 수 있는 신호가 된다.
 * <p>
 * 이 신호는 상태를 나르지 않는다. Incident를 읽지도, Metric을 읽지도 않는다. 이것이 상태를 실어
 * 나르기 시작하면 REST 조회 말고 상태를 아는 자리가 하나 더 생기고, 화면이 어느 쪽을 믿을지 정해야
 * 한다. 무엇이 참인지는 여전히 REST가 정한다.
 * <p>
 * Incident의 lifecycle과 무관하다. 변경 알림은 Transaction이 commit된 뒤에만 나가지만 이 신호는
 * Transaction과 아무 관계가 없다. 그래서 판정이나 확인 처리 쪽 코드에 이 책임을 두지 않고 SSE를
 * 다루는 이 자리에만 둔다.
 * <p>
 * 주기 실행에 Spring의 Scheduler를 켜지 않고 전용 Thread 하나를 쓴다. {@code @EnableScheduling}은
 * Application 전체에 시간 기반 실행을 여는 일이라, 지금 필요한 것이 SSE 연결 하나의 사정일 뿐인데
 * 그보다 훨씬 넓은 것을 켜게 된다. 그렇게 열어 두면 이후에 생길 시간 기반 Business 작업과 이 신호가
 * 같은 Scheduler를 나누어 쓰게 되고, 그쪽이 오래 걸리는 날 이 신호가 함께 밀린다.
 * <p>
 * 보내는 일은 스스로 하지 않고 {@link IncidentChangedEventListener}와 같은 Executor에 넘긴다. 그래야
 * 연결 하나에 쓰는 일이 언제나 한 Thread에서만 일어난다. 여기서 직접 보내면 변경 알림을 쓰는 중인
 * 연결에 다른 Thread가 동시에 쓰게 되고, 그러면 두 Event의 글자가 서로 섞일 수 있다. 대신 이 Thread는
 * 넘기는 일만 하므로 붙들리는 시간이 없다.
 */
@Component
public class IncidentSseHeartbeatScheduler {

	/**
	 * 이 간격으로 신호를 보낸다.
	 * <p>
	 * Client는 이 값보다 넉넉히 큰 시간 동안 아무것도 오지 않을 때에만 끊겼다고 판단한다. 그래야 한
	 * 번의 지연이나 유실로 멀쩡한 연결을 다시 만들지 않는다. 그 판단 기준은 Client가 정하지만, 그것이
	 * 이 값에 기대고 있다는 사실은 양쪽에 적어 둔다.
	 * <p>
	 * 더 짧게 잡을 이유가 없다. 이 값이 곧 끊김을 알아채기까지의 시간은 아니고, Client가 몇 번을
	 * 놓쳐야 판단할지가 그것을 정한다. 짧게 잡으면 아무 일도 일어나지 않는 Application에서 오가는
	 * 줄만 늘어난다.
	 */
	static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(20);

	private static final Logger logger = LoggerFactory.getLogger(IncidentSseHeartbeatScheduler.class);

	private final IncidentSseBroadcaster incidentSseBroadcaster;

	private final TaskExecutor incidentSseExecutor;

	/**
	 * Thread 하나짜리 Scheduler이다. 하는 일이 주기마다 일감을 넘기는 것뿐이라 더 필요하지 않다.
	 * 이름을 붙이는 것은 Thread 목록에서 이것이 무엇인지 알아볼 수 있어야 하기 때문이고, daemon으로
	 * 두는 것은 이 Thread가 Application의 종료를 막지 않아야 하기 때문이다.
	 */
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor((runnable) -> {
		Thread thread = new Thread(runnable, "incident-sse-heartbeat");
		thread.setDaemon(true);
		return thread;
	});

	public IncidentSseHeartbeatScheduler(IncidentSseBroadcaster incidentSseBroadcaster,
			TaskExecutor incidentSseExecutor) {
		this.incidentSseBroadcaster = incidentSseBroadcaster;
		this.incidentSseExecutor = incidentSseExecutor;
	}

	/**
	 * 첫 신호도 한 주기를 기다린 뒤에 보낸다. 연결이 열리는 순간에는 연결 Event가 이미 나가므로 그
	 * 직후에 같은 뜻의 신호를 한 번 더 보낼 이유가 없다.
	 */
	@PostConstruct
	void start() {
		long intervalMillis = HEARTBEAT_INTERVAL.toMillis();
		this.scheduler.scheduleAtFixedRate(this::tick, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
	}

	/**
	 * Application이 끝날 때 이 Thread도 끝낸다. 남겨 두면 이미 닫힌 연결에 계속 쓰려 든다.
	 */
	@PreDestroy
	void stop() {
		this.scheduler.shutdownNow();
	}

	/**
	 * 한 주기에 하는 일이다.
	 * <p>
	 * 예외를 여기서 모두 붙잡는 것이 이 method의 핵심이다. 주기 실행은 맡긴 일이 한 번이라도 예외로
	 * 끝나면 그 뒤로 다시 불리지 않는다. 그러면 신호가 조용히 멈추고, Client는 서버가 죽은 것과
	 * 구분하지 못해 멀쩡한 연결을 계속 다시 만든다. 넘기다 실패하는 것은 대기열이 가득 찬 순간뿐이며
	 * 그것은 설계상 정상이지만, 그 하나 때문에 이후의 모든 주기를 잃는 것은 정상이 아니다.
	 * <p>
	 * 버린 신호를 다시 보내지 않는다. 다음 주기가 곧 온다.
	 */
	void tick() {
		try {
			this.incidentSseExecutor.execute(this.incidentSseBroadcaster::sendHeartbeat);
		}
		catch (RuntimeException ex) {
			logger.warn("SSE heartbeat를 보내지 못해 이번 주기를 건너뛴다. reason={}", ex.getClass().getSimpleName());
		}
	}

}

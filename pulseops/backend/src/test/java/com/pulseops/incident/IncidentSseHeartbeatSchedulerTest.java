package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.TaskRejectedException;

/**
 * 한 주기에 무엇을 하는지와, 그 한 번이 실패했을 때 다음 주기가 살아남는지를 검증한다.
 * <p>
 * 주기가 실제로 20초마다 오는지는 여기서 보지 않는다. 그것을 확인하려면 그만큼 기다려야 하고, 기다려서
 * 통과하는 검증은 느린 만큼 흔들린다. 대신 한 주기가 하는 일을 직접 부른다. 이 Class에서 정하는 것은
 * 주기마다 무엇을 하느냐이고, 그것을 몇 밀리초마다 부를지는 표준 Scheduler가 하는 일이다.
 */
@ExtendWith(OutputCaptureExtension.class)
class IncidentSseHeartbeatSchedulerTest {

	/** 건너뛴 주기를 log에서 찾기 위한 표시이다. */
	private static final String SKIPPED_LOG_MARKER = "SSE heartbeat를 보내지 못해";

	private final IncidentSseBroadcaster broadcaster = mock(IncidentSseBroadcaster.class);

	/**
	 * 이 Thread는 주기를 재는 일만 한다. 여기서 직접 보내면 응답하지 않는 Client 하나에 쓰는 시간이
	 * 다음 주기를 통째로 밀어낸다.
	 */
	@Test
	void handsTheHeartbeatToAnotherThread() {
		List<Runnable> handedOver = new ArrayList<>();
		IncidentSseHeartbeatScheduler scheduler = new IncidentSseHeartbeatScheduler(this.broadcaster, handedOver::add);

		scheduler.tick();

		then(this.broadcaster).shouldHaveNoInteractions();
		assertThat(handedOver).hasSize(1);
	}

	/** 넘긴 일이 실제로 살아 있다는 신호를 보내는 일이어야 한다. */
	@Test
	void sendsTheHeartbeatThatWasHandedOver() {
		List<Runnable> handedOver = new ArrayList<>();
		IncidentSseHeartbeatScheduler scheduler = new IncidentSseHeartbeatScheduler(this.broadcaster, handedOver::add);
		scheduler.tick();

		handedOver.get(0).run();

		then(this.broadcaster).should().sendHeartbeat();
	}

	/**
	 * 주기 실행은 맡긴 일이 예외로 끝나면 그 뒤로 다시 불리지 않는다. 대기열이 가득 차 한 번을 버리는
	 * 것은 설계상 정상인데, 그 하나 때문에 신호가 영원히 멈추면 Client는 서버가 죽은 것과 구분하지
	 * 못해 멀쩡한 연결을 계속 다시 만든다.
	 */
	@Test
	void keepsTickingAfterAFailedTick(CapturedOutput output) {
		List<Runnable> handedOver = new ArrayList<>();
		boolean[] rejectOnce = { true };
		IncidentSseHeartbeatScheduler scheduler = new IncidentSseHeartbeatScheduler(this.broadcaster, (task) -> {
			if (rejectOnce[0]) {
				rejectOnce[0] = false;
				throw new TaskRejectedException("대기열이 가득 찼다");
			}
			handedOver.add(task);
		});

		assertThatNoException().isThrownBy(scheduler::tick);
		scheduler.tick();

		assertThat(handedOver).hasSize(1);
		assertThat(output).contains(SKIPPED_LOG_MARKER);
	}

	/** 버린 신호를 다시 보내지 않는다. 다음 주기가 곧 온다. */
	@Test
	void neverRetriesASkippedTick() {
		IncidentSseHeartbeatScheduler scheduler = new IncidentSseHeartbeatScheduler(this.broadcaster, (task) -> {
			throw new TaskRejectedException("대기열이 가득 찼다");
		});

		scheduler.tick();

		then(this.broadcaster).should(never()).sendHeartbeat();
	}

	/** 한 주기는 한 번만 보낸다. 주기마다 여러 번 보내면 Client가 세는 간격이 뜻을 잃는다. */
	@Test
	void sendsOneHeartbeatPerTick() {
		List<Runnable> handedOver = new ArrayList<>();
		IncidentSseHeartbeatScheduler scheduler = new IncidentSseHeartbeatScheduler(this.broadcaster, handedOver::add);

		scheduler.tick();
		scheduler.tick();

		handedOver.forEach(Runnable::run);
		then(this.broadcaster).should(times(2)).sendHeartbeat();
	}

}

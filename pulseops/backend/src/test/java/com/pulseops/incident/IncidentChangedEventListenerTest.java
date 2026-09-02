package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.TaskRejectedException;

/**
 * 알림을 어느 Thread로 넘기는지, 넘길 자리가 없을 때 무엇을 하는지를 검증한다.
 * <p>
 * commit 이후에만 불린다는 계약은 여기서 볼 수 없다. 그것은 Spring이 실제 Transaction 위에서 지키는
 * 일이라 대역으로는 흉내만 낼 수 있고, {@link IncidentChangedEventListenerIntegrationTest}가 실제
 * Transaction으로 고정한다. 여기서는 불린 다음에 무엇을 하는가만 본다.
 * <p>
 * Executor를 흉내 내는 이유는 대기열이 가득 찬 순간을 만들기 위해서이다. 실제 Executor로 그 순간을
 * 만들려면 Thread를 붙들고 대기열을 끝까지 채워야 하는데, 여기서 볼 것은 대기열이 어떻게 차는가가
 * 아니라 거절을 받았을 때 무엇을 하는가이다.
 */
@ExtendWith(OutputCaptureExtension.class)
class IncidentChangedEventListenerTest {

	/** 버려진 알림을 log에서 찾기 위한 표시이다. */
	private static final String QUEUE_FULL_LOG_MARKER = "SSE 전송 대기열이 가득 차";

	private static final IncidentChangedEvent CHANGE = new IncidentChangedEvent(7L,
			Instant.parse("2026-09-01T06:00:00Z"));

	private final IncidentSseBroadcaster broadcaster = mock(IncidentSseBroadcaster.class);

	/**
	 * 이 method는 확인 처리를 요청한 사람의 HTTP Thread나 Kafka Consumer Thread 위에서 돈다. 여기서
	 * 직접 보내면 응답하지 않는 Client 하나에 쓰는 시간이 그 Thread를 붙든다.
	 */
	@Test
	void handsTheAnnouncementToAnotherThread() {
		List<Runnable> handedOver = new ArrayList<>();
		IncidentChangedEventListener listener = new IncidentChangedEventListener(this.broadcaster, handedOver::add);

		listener.onIncidentChanged(CHANGE);

		then(this.broadcaster).shouldHaveNoInteractions();
		assertThat(handedOver).hasSize(1);
	}

	/** 넘긴 일이 실제로 그 변경을 알리는 일이어야 한다. */
	@Test
	void announcesTheChangeThatWasHandedOver() {
		List<Runnable> handedOver = new ArrayList<>();
		IncidentChangedEventListener listener = new IncidentChangedEventListener(this.broadcaster, handedOver::add);
		listener.onIncidentChanged(CHANGE);

		handedOver.get(0).run();

		then(this.broadcaster).should().broadcast(CHANGE);
	}

	/**
	 * 대기열이 가득 차면 알림을 버린다. 변경은 이미 저장되어 되돌릴 수 없고, 여기서 예외를 내보내면
	 * 그것이 확인 응답이나 Kafka 소비 쪽으로 번진다. 다시 시도하지도 않는다. 그러려면 이 Thread를
	 * 붙들거나 또 하나의 대기열을 만들어야 하는데, 그동안 막히는 것은 사람이 기다리는 응답과 관측이다.
	 */
	@Test
	void dropsTheAnnouncementWhenTheQueueIsFull(CapturedOutput output) {
		IncidentChangedEventListener listener = new IncidentChangedEventListener(this.broadcaster, (task) -> {
			throw new TaskRejectedException("대기열이 가득 찼다");
		});

		assertThatNoException().isThrownBy(() -> listener.onIncidentChanged(CHANGE));

		then(this.broadcaster).should(never()).broadcast(any());
		assertThat(output).contains(QUEUE_FULL_LOG_MARKER).contains(String.valueOf(CHANGE.incidentId()));
	}

}

package com.pulseops.incident;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Incident 변경이 실제로 저장된 뒤에만 알림을 내보낸다.
 * <p>
 * commit 이후로 미루는 것이 이 Class의 존재 이유이다. Incident를 바꾸는 쪽은 알림을 Transaction 안에서
 * 알리지만 그 Transaction은 아직 되돌아갈 수 있다. 바뀌자마자 알리면 되돌아간 변경까지 알리게 되고,
 * 알림을 받은 화면은 아무것도 달라지지 않은 상태를 다시 읽고는 자기가 늦은 것인지 서버가 틀린 것인지
 * 알 수 없게 된다.
 * <p>
 * 되돌아간 Transaction에서는 아무 일도 하지 않는다. Spring이 commit된 경우에만 이 method를 부르므로
 * 여기에 성공 여부를 다시 판단하는 자리를 두지 않는다.
 * <p>
 * Incident를 다시 읽지 않는다. 알림에는 어느 Incident가 언제 바뀌었는지만 있으면 되고, 최신 상태는
 * Client가 REST로 읽는다. 여기서 상태를 만들어 실어 보내면 알림이 상태 전달 수단이 되고, 알림을 놓친
 * 화면과 받은 화면이 서로 다른 것을 믿게 된다.
 * <p>
 * 여기서 직접 보내지 않고 전용 Thread에 넘기기만 한다. 이 method는 commit을 끝낸 Thread 위에서 도는데,
 * 그 Thread는 확인 처리를 요청한 사람의 HTTP Thread이거나 Kafka Consumer Thread이다. 넘기는 일만 하면
 * 그 Thread가 붙들리는 시간은 무시할 수 있다.
 * <p>
 * {@code @Async}를 쓰지 않고 직접 넘기는 이유는 거절을 다루기 위해서이다. {@code @Async}로 두면 대기열이
 * 찼을 때 던져지는 예외를 Spring이 Transaction 동기화 바깥에서 삼키며 stack trace가 붙은 ERROR로 남긴다.
 * 알림 하나를 버리는 것은 설계상 정상인데 그때마다 ERROR가 쌓이면 실제 장애를 찾을 때 걸러 낼 줄만
 * 늘어난다. 직접 넘기면 그 순간을 여기서 붙잡아 한 줄의 WARN으로 남길 수 있다.
 */
@Component
public class IncidentChangedEventListener {

	private static final Logger logger = LoggerFactory.getLogger(IncidentChangedEventListener.class);

	private final IncidentSseBroadcaster incidentSseBroadcaster;

	private final TaskExecutor incidentSseExecutor;

	public IncidentChangedEventListener(IncidentSseBroadcaster incidentSseBroadcaster,
			TaskExecutor incidentSseExecutor) {
		this.incidentSseBroadcaster = incidentSseBroadcaster;
		this.incidentSseExecutor = incidentSseExecutor;
	}

	/**
	 * 대기열이 가득 차면 이 알림을 버린다.
	 * <p>
	 * 버리는 것이 유일하게 맞는 선택이다. 변경은 이미 저장되어 되돌릴 수 없고, 다시 시도하려면 이
	 * Thread를 붙들거나 또 다른 대기열을 만들어야 한다. 그동안 막히는 것은 사람이 기다리는 확인 응답과
	 * Kafka 소비이다. 알림을 놓친 화면은 다음 알림이나 재연결로 따라잡지만, 관측이 멈추면 그 구간은
	 * 돌아오지 않는다.
	 * <p>
	 * 무엇을 버렸는지는 남긴다. 대기열이 넘칠 만큼 알림이 몰렸거나 전송이 느려졌다는 뜻이라 사람이
	 * 알아야 할 사실이다.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onIncidentChanged(IncidentChangedEvent event) {
		try {
			this.incidentSseExecutor.execute(() -> this.incidentSseBroadcaster.broadcast(event));
		}
		catch (TaskRejectedException ex) {
			logger.warn("SSE 전송 대기열이 가득 차 Incident 변경 알림을 버린다. incidentId={}", event.incidentId());
		}
	}

}

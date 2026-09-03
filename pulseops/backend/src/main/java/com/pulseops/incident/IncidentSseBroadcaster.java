package com.pulseops.incident;

import com.pulseops.incident.dto.IncidentStreamConnectedResponse;
import com.pulseops.incident.dto.IncidentStreamHeartbeatResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 지금 열려 있는 SSE 연결들을 들고 있다가 Incident가 바뀌었다는 사실을 그 전부에게 흘려보낸다.
 * <p>
 * 알림은 언제나 최선을 다할 뿐이다. 받지 못한 Client가 있어도 다시 보내지 않고, 어디까지 보냈는지도
 * 남기지 않는다. 상태를 아는 곳은 REST 조회이고 알림은 그것을 다시 부르라는 신호일 뿐이라, 신호 하나를
 * 놓친 화면은 다음 신호나 재연결로 따라잡는다. 유실을 막으려면 보낸 것을 저장하고 순서를 매기고 다시
 * 보내야 하는데, 그것은 알림이 아니라 또 하나의 상태 저장소이다.
 * <p>
 * 구독을 Incident별로 나누지 않는다. Dashboard는 처리 중인 Incident 전부를 보고 있어 어느 것이 바뀌든
 * 같은 목록을 다시 읽는다. 관심 대상을 나누어 두면 Client가 무엇을 구독 중인지를 서버가 따라다녀야
 * 하고, 그 관리 비용이 지금 얻는 것보다 크다.
 * <p>
 * 연결 목록은 이 Instance의 memory에만 있다. PulseOps Backend를 여러 벌 띄우면 한 벌이 받은 변경은 그
 * 벌에 붙은 Client에게만 간다. 지금은 한 벌만 띄우므로 그대로 두고, 여러 벌이 필요해지는 시점에
 * Broker를 사이에 두어야 한다는 사실을 여기에 적어 둔다.
 * <p>
 * 흘려보내는 것이 하나 더 있다. 연결이 아직 살아 있다는 신호이다. 그것도 상태가 아니라 신호이고 같은
 * 연결 목록 위에서 같은 실패 처리를 거치므로 여기에 둔다. 언제 보낼지는 여기서 정하지 않는다.
 * {@link IncidentSseHeartbeatScheduler}가 주기를 정해 부른다.
 * <p>
 * 관리하는 {@link SseEmitter}를 스스로 만들지 않고 밖에서 받는다. 연결이 얼마나 살아 있을지는 HTTP
 * 응답의 성질이라 그것을 정하는 자리는 Controller이고, 여기서는 살아 있는 구독의 집합만 안다. 덕분에
 * 이 Class는 실제 HTTP 없이도 그대로 검증할 수 있다.
 */
@Component
public class IncidentSseBroadcaster {

	/** 연결이 실제로 열렸음을 알리는 Event 이름이다. Client는 이 이름으로 첫 Event를 구분한다. */
	private static final String CONNECTED_EVENT = "connected";

	/** Incident가 바뀌었으니 다시 읽으라는 Event 이름이다. */
	private static final String INCIDENT_CHANGED_EVENT = "incident-changed";

	/** 연결이 아직 살아 있다는 것만 알리는 Event 이름이다. Client는 이것으로 다시 읽지 않는다. */
	private static final String HEARTBEAT_EVENT = "heartbeat";

	private static final Logger logger = LoggerFactory.getLogger(IncidentSseBroadcaster.class);

	/**
	 * 여러 Thread가 동시에 드나든다. 구독은 Servlet Thread가 더하고, 전송은 SSE 전용 Thread가 돌며,
	 * 연결이 끊어졌다는 통보는 Container가 자기 Thread에서 준다. 그래서 목록을 도는 도중에 다른
	 * Thread가 지워도 안전한 자료구조를 쓴다. 전체를 잠그면 한 Client에게 쓰는 시간이 다른 Client의
	 * 구독과 해지까지 막는다.
	 */
	private final Map<String, SseEmitter> subscribers = new ConcurrentHashMap<>();

	/**
	 * 연결 하나를 목록에 넣고 열렸다는 사실을 곧바로 알린다.
	 * <p>
	 * 끊어졌을 때의 정리를 먼저 걸어 둔 뒤에 목록에 넣는다. 순서가 반대이면 넣은 직후 끊어진 연결이
	 * 정리되지 않고 남는다. 세 가지 통보를 모두 받는 이유는 연결이 끝나는 길이 셋이기 때문이다.
	 * 정상 종료, 시간 초과, 오류 중 하나만 빠뜨려도 그 길로 끝난 연결이 목록에 영원히 쌓인다. 같은
	 * 연결에 대해 두 번 통보가 와도 지우는 일은 한 번 더 해도 결과가 같다.
	 * <p>
	 * 첫 Event를 보내다 실패하면 목록에서 다시 뺀다. 실패한 연결은 Container가 끝내 주기를 기다릴 수
	 * 없다. 아직 응답이 시작되지도 않아 끊어졌다는 통보가 오지 않을 수 있고, 그러면 아무에게도 닿지
	 * 않는 연결이 목록에 남아 이후의 모든 전송이 그 자리에서 실패한다.
	 */
	public void subscribe(SseEmitter emitter) {
		String subscriberId = UUID.randomUUID().toString();
		emitter.onCompletion(() -> this.subscribers.remove(subscriberId));
		emitter.onTimeout(() -> this.subscribers.remove(subscriberId));
		emitter.onError((throwable) -> this.subscribers.remove(subscriberId));
		this.subscribers.put(subscriberId, emitter);
		try {
			emitter.send(SseEmitter.event()
				.name(CONNECTED_EVENT)
				.data(new IncidentStreamConnectedResponse(Instant.now())));
		}
		catch (IOException | IllegalStateException ex) {
			logger.warn("SSE 연결을 열었다고 알리지 못해 구독을 취소한다. subscriberId={} reason={}", subscriberId,
					ex.getClass().getSimpleName());
			unsubscribe(subscriberId, emitter, ex);
		}
	}

	/**
	 * 열려 있는 모든 연결에 Incident가 바뀌었다고 알린다.
	 * <p>
	 * 한 연결이 실패해도 나머지는 그대로 받는다. 알림은 Client마다 독립적인 일이고, 이미 끊어진
	 * Client 하나 때문에 살아 있는 화면들이 변경을 놓치면 안 된다.
	 * <p>
	 * 구독이 하나도 없으면 아무 일도 하지 않는다. 보고 있는 사람이 없는 것은 정상이며 log로 남길
	 * 일이 아니다. Incident는 아무도 화면을 열지 않은 새벽에도 열리고 닫힌다.
	 */
	public void broadcast(IncidentChangedEvent event) {
		this.subscribers.forEach((subscriberId, emitter) -> send(subscriberId, emitter, event));
	}

	/**
	 * 열려 있는 모든 연결에 아직 살아 있다는 신호만 보낸다.
	 * <p>
	 * 이것이 있어야 Client가 끊긴 연결을 알아챌 수 있다. Backend Process가 사라지면 연결을 끊어 줄
	 * Stream 시간 초과도 그 Process와 함께 사라지고, 중간의 Proxy가 Client 쪽 연결을 붙들고 있으면
	 * Client에게는 오류도 종료도 도착하지 않는다. 그러면 EventSource는 열린 채로 남아 스스로 다시
	 * 잇지 않는다. 주기적으로 무엇인가 보내기로 약속해 두면 그것이 오지 않는 것이 곧 신호가 된다.
	 * <p>
	 * 상태를 싣지 않는다. 이 신호는 연결이 살아 있다는 사실만 말하며, 무엇이 참인지는 여전히 REST
	 * 조회가 정한다. 여기에 상태를 실으면 화면이 주기적으로 도착하는 값을 믿기 시작하고, 이 Stream이
	 * 신호가 아니라 또 하나의 상태 전달 수단이 된다.
	 * <p>
	 * 보고 있는 사람이 없으면 아무 일도 하지 않는다. 이 신호 때문에 저장소를 읽는 일도 없다.
	 */
	public void sendHeartbeat() {
		this.subscribers.forEach(this::sendHeartbeat);
	}

	/**
	 * 정리되지 않고 쌓이는 구독이 있는지 확인하기 위한 자리이다. API로 내보내지 않는다. 지금 몇 명이
	 * 보고 있는지는 운영자가 알아야 할 값이 아니고, 그것을 Endpoint로 열면 없애기 어려운 계약이 된다.
	 */
	int subscriberCount() {
		return this.subscribers.size();
	}

	private void send(String subscriberId, SseEmitter emitter, IncidentChangedEvent event) {
		try {
			emitter.send(SseEmitter.event().name(INCIDENT_CHANGED_EVENT).data(event));
		}
		// 이미 끊어진 연결에 쓰면 IOException이고, 이미 끝난 Emitter에 쓰면 IllegalStateException이다.
		// 둘 다 이 연결이 더는 쓸 수 없다는 같은 뜻이므로 같이 정리한다. 더 넓게 잡으면 직렬화 결함처럼
		// 모든 Client에게 똑같이 일어나는 문제가 연결 하나의 사정으로 묻힌다.
		catch (IOException | IllegalStateException ex) {
			logger.warn("SSE 구독자에게 Incident 변경을 전하지 못해 구독을 취소한다. subscriberId={} incidentId={} reason={}",
					subscriberId, event.incidentId(), ex.getClass().getSimpleName());
			unsubscribe(subscriberId, emitter, ex);
		}
	}

	/**
	 * 실패한 연결을 곧바로 목록에서 뺀다. 남겨 두면 이 신호는 주기적으로 반복되므로 죽은 연결 하나가
	 * 주기마다 같은 log를 찍는다.
	 */
	private void sendHeartbeat(String subscriberId, SseEmitter emitter) {
		try {
			emitter.send(SseEmitter.event()
				.name(HEARTBEAT_EVENT)
				.data(new IncidentStreamHeartbeatResponse(Instant.now())));
		}
		// 변경 알림과 같은 이유로 두 예외를 함께 다룬다. 다시 시도하지 않는 것도 같다.
		catch (IOException | IllegalStateException ex) {
			logger.warn("SSE 구독자에게 heartbeat를 전하지 못해 구독을 취소한다. subscriberId={} reason={}", subscriberId,
					ex.getClass().getSimpleName());
			unsubscribe(subscriberId, emitter, ex);
		}
	}

	/**
	 * 쓸 수 없게 된 연결을 목록에서 빼고 그 연결도 끝낸다.
	 * <p>
	 * 목록에서 빼는 것만으로는 부족하다. Container 쪽에서 보면 그 요청은 여전히 응답을 기다리는
	 * 중이라, 끝내지 않으면 시간 초과가 올 때까지 자원을 붙들고 있다. 끝내면 Container가 통보를
	 * 돌려주고 걸어 둔 정리가 한 번 더 돌지만, 이미 빠진 것을 다시 빼는 것뿐이라 되돌이가 되지 않는다.
	 */
	private void unsubscribe(String subscriberId, SseEmitter emitter, Throwable cause) {
		this.subscribers.remove(subscriberId);
		emitter.completeWithError(cause);
	}

}

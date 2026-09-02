package com.pulseops.incident;

import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Dashboard가 Incident 변경을 기다릴 수 있도록 SSE 연결 하나를 연다.
 * <p>
 * {@link IncidentController}와 나누는 이유는 이 Endpoint가 값을 돌려주지 않기 때문이다. 나머지 셋은
 * 요청 하나에 상태 하나를 돌려주고 끝나지만, 여기서는 응답을 열어 둔 채 오래 살아 있는 연결을 만들고
 * 그 수명과 정리까지 다룬다. 다루는 것이 값이 아니라 연결이므로 같은 Class에 두면 두 종류의 책임이
 * 섞인다.
 * <p>
 * 경로는 {@code /api/incidents} 아래에 둔다. 무엇에 관한 알림인지가 곧 이 Endpoint의 뜻이고, 알림
 * 전용 경로를 따로 만들면 같은 Incident를 가리키는 주소가 두 갈래가 된다. {@code /{incidentId}}와
 * 같은 자리에 있지만 변수가 없는 경로가 더 구체적이므로 {@code /stream}이 먼저 선택된다.
 * <p>
 * 이 연결로는 상태를 보내지 않는다. Client는 알림을 받을 때마다 기존 조회 API를 다시 부른다. 그래서
 * 알림을 놓치거나 연결이 끊겨도 화면이 낡은 상태에 갇히지 않는다.
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentStreamController {

	/**
	 * 연결을 이만큼 열어 둔 뒤 서버가 스스로 끊는다. 끝나지 않는 연결을 두면 끊긴 것을 알아채지 못한
	 * Client의 자리가 계속 쌓이고, 그 자리는 Container가 회수해 주지 않는다. 끊긴 뒤 다시 잇는 것은
	 * Client의 EventSource가 스스로 하는 일이라, 이 시간은 화면이 알림을 못 받는 구간이 아니라 서버가
	 * 죽은 연결을 정리하는 주기이다.
	 */
	private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(5).toMillis();

	private final IncidentSseBroadcaster incidentSseBroadcaster;

	public IncidentStreamController(IncidentSseBroadcaster incidentSseBroadcaster) {
		this.incidentSseBroadcaster = incidentSseBroadcaster;
	}

	/**
	 * 연결을 만들어 구독에 넣고 그대로 돌려준다.
	 * <p>
	 * 만드는 것은 여기, 들고 있는 것은 {@link IncidentSseBroadcaster}이다. 연결이 얼마나 살아 있을지는
	 * HTTP 응답을 어떻게 다룰지의 문제이고, 지금 누가 보고 있는지는 알림을 보내는 쪽의 문제이다.
	 */
	@GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter streamIncidentChanges() {
		SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
		this.incidentSseBroadcaster.subscribe(emitter);
		return emitter;
	}

}

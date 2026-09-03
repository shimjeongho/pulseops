package com.pulseops.incident.dto;

import java.time.Instant;

/**
 * 연결이 아직 살아 있다는 것만 알리는 Event의 본문이다.
 * <p>
 * 이 Event가 필요한 이유는 Client가 연결이 끊어졌다는 사실을 스스로 알아내지 못하는 구간이 있기
 * 때문이다. Backend Process가 사라지면 그 안에서 돌던 Stream 시간 초과도 함께 사라지고, 중간의
 * Proxy가 Client 쪽 연결을 그대로 붙들고 있으면 Client에게는 오류도 종료도 오지 않는다. 그러면
 * EventSource는 열린 상태로 남아 스스로 다시 잇지 않는다. 주기적으로 무엇인가 도착하기로 약속해
 * 두면, 그것이 오지 않는 것 자체가 Client가 판단할 수 있는 신호가 된다.
 * <p>
 * 상태를 담지 않는다. Incident도 Metric도 서버의 상태 요약도 싣지 않는다. 여기에 상태를 실으면 이
 * Event가 주기적인 조회가 되고, 화면이 REST 대신 이것을 믿기 시작한다. 담긴 시각은 무엇이 바뀌었다는
 * 뜻이 아니라 이 신호를 언제 보냈는가일 뿐이다.
 */
public record IncidentStreamHeartbeatResponse(Instant sentAt) {

}

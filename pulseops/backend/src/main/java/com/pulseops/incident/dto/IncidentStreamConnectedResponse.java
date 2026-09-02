package com.pulseops.incident.dto;

import java.time.Instant;

/**
 * SSE 연결이 실제로 열렸다는 것을 알리는 첫 Event의 본문이다.
 * <p>
 * 이 Event가 필요한 이유는 HTTP 응답 헤더만으로는 연결이 살아 있는지 알 수 없기 때문이다. 200과
 * Content-Type은 Proxy나 Buffer가 중간에서 돌려줄 수도 있어, 첫 Event가 실제로 도착하기 전까지 Client는
 * Stream이 이어졌는지 확인할 방법이 없다.
 * <p>
 * Incident에 관한 값은 담지 않는다. 연결 직후 화면이 무엇을 그릴지는 REST 조회가 정하고, 여기에 처리
 * 중인 Incident를 실어 보내면 같은 목록을 만드는 자리가 둘이 된다.
 */
public record IncidentStreamConnectedResponse(Instant connectedAt) {

}

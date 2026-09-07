package com.pulseops.incident;

/**
 * 처리 중인 Incident를 종류와 심각도로 나눠 센 결과 한 줄이다.
 * <p>
 * Entity를 읽지 않고 DB에서 세어 온다. 관측을 위해 처리 중인 Incident를 전부 Entity로 읽으면 Prometheus가
 * 긁어 갈 때마다 영속성 Context에 쓰이지 않을 객체가 쌓인다.
 *
 * @param type Incident 종류
 * @param severity 지금의 심각도. 심각도는 올라갈 수 있으므로 이 값은 열렸을 때의 심각도가 아니다.
 * @param count 그 조합에 해당하는 처리 중인 Incident 수
 */
public record ActiveIncidentCount(IncidentType type, IncidentSeverity severity, long count) {

}

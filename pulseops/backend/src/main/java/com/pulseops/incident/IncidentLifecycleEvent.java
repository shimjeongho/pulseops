package com.pulseops.incident;

/**
 * Incident 하나가 세어야 할 상태 전이를 겪었다는 사실이다. 관측값을 세기 위해서만 쓰인다.
 * <p>
 * {@link IncidentChangedEvent}와 나누어 둔다. 그쪽은 "어느 Incident가 언제 달라졌으니 다시 읽어라"라는
 * 화면을 향한 신호이고 무엇이 어떻게 달라졌는지를 일부러 담지 않는다. 세는 쪽은 정확히 그 담기지 않은
 * 값이 있어야 하므로, 화면용 알림에 종류와 심각도를 얹어 두 목적을 한 Event에 겹치지 않는다.
 * <p>
 * Incident ID를 담지 않는다. 이 Event가 닿는 곳은 Counter 하나뿐이고 Counter는 몇 번 일어났는지만
 * 세므로, ID를 실어 두면 어디에도 쓰이지 않는 값이 metric 경로를 따라 흐른다.
 *
 * @param transition 무슨 일이 일어났는지
 * @param type 그 Incident의 종류. 열린 뒤 바뀌지 않는다.
 * @param severity 전이가 일어난 순간의 심각도. 심각도는 열린 뒤 올라갈 수 있으므로 여는 전이와 끝내는
 * 전이가 서로 다른 값을 가질 수 있다.
 */
public record IncidentLifecycleEvent(IncidentLifecycleTransition transition, IncidentType type,
		IncidentSeverity severity) {

}

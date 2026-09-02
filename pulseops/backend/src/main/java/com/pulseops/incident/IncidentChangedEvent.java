package com.pulseops.incident;

import java.time.Instant;

/**
 * Incident 하나의 상태가 실제로 달라졌다는 사실이다. Dashboard에게 "다시 읽어라"라고 알리기 위해서만
 * 쓰인다.
 * <p>
 * 무엇이 어떻게 달라졌는지는 담지 않는다. 상태를 아는 곳은 REST 조회 하나여야 하고, 같은 상태가 알림에도
 * 실리면 화면이 어느 쪽을 믿을지 정해야 한다. 알림은 유실될 수 있고 순서도 보장되지 않으므로, 그것을
 * 믿기 시작하면 화면이 실제와 다른 상태를 오래 붙들고 있게 된다. 받은 쪽이 다시 조회하면 그 순간의
 * 확정된 상태가 나온다.
 * <p>
 * occurredAt은 이 알림을 만든 시각이 아니라 Incident를 바꾼 전이의 시각이다. 부르는 쪽이 상태 전이에
 * 넘긴 것과 같은 값을 그대로 넘겨야 한다. 여기서 시계를 다시 읽으면 같은 전이가 두 개의 시각을 갖게
 * 되고, 그 미세한 차이는 나중에 알림과 Incident를 나란히 놓고 볼 때 설명할 수 없는 어긋남이 된다.
 * <p>
 * Event 자체는 어떤 전달 수단도 알지 않는다. SSE는 지금 이 알림을 나르는 하나의 방법일 뿐이고,
 * Incident를 바꾸는 쪽이 그 방법을 알면 전달 수단이 바뀔 때 상태 전이 코드가 함께 바뀐다.
 */
public record IncidentChangedEvent(Long incidentId, Instant occurredAt) {

}

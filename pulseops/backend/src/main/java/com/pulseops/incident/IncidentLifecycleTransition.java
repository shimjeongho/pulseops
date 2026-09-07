package com.pulseops.incident;

/**
 * Incident가 겪은 상태 전이 중 관측 대상으로 세는 것들이다.
 * <p>
 * {@link IncidentStatus}와 다르다. 그쪽은 Incident가 지금 어떤 상태인지를 뜻하고, 이것은 그 상태로
 * 옮겨 가는 일이 한 번 일어났다는 사실을 뜻한다. 상태는 되돌아보면 하나이지만 전이는 세어야 하는
 * 사건이라 서로 다른 값이다.
 * <p>
 * 확인 처리(ACKNOWLEDGED로의 전이)와 심각도 상승은 여기에 없다. 둘 다 일어나는 사건이지만 Phase 12가
 * 관측하기로 한 것은 장애가 몇 번 열리고 몇 번 끝났는가이며, 세지 않을 값을 미리 만들어 두면 아무도
 * 보지 않는 시계열이 남는다.
 */
public enum IncidentLifecycleTransition {

	/** 새 Incident가 열렸다. */
	OPENED,

	/** 처리 중이던 Incident가 끝났다. */
	RESOLVED

}

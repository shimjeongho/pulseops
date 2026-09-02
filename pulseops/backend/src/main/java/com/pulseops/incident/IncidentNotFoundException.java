package com.pulseops.incident;

/**
 * 요청한 ID에 해당하는 Incident가 없을 때 발생한다.
 * <p>
 * 끝난 Incident도 조회 대상이므로 이 예외는 "지금 처리 중이 아니다"가 아니라 "그런 기록이 없다"만
 * 뜻한다. 상태로 걸러진 결과를 없는 것처럼 알리면, 목록에서 사라졌다는 이유로 상세를 열 수 없는
 * 것과 실제로 없는 것을 Client가 구분하지 못한다.
 */
public class IncidentNotFoundException extends RuntimeException {

	public IncidentNotFoundException(Long incidentId) {
		super("Incident not found: " + incidentId);
	}

}

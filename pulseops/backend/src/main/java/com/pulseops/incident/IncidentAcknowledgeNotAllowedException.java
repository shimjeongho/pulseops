package com.pulseops.incident;

/**
 * 지금 상태에서는 Incident를 확인 처리할 수 없을 때 발생한다.
 * <p>
 * 이미 확인된 Incident를 다시 확인하는 것과 끝난 Incident를 확인하는 것을 하나의 뜻으로 묶는다.
 * 부르는 쪽이 알아야 하는 것은 "확인 처리로 넘어갈 수 없다"이고, 왜 넘어갈 수 없는지는 같은 응답의
 * 상태를 다시 읽으면 알 수 있다. 상태마다 code를 쪼개면 Client가 상태 전이 규칙을 자기 쪽에도
 * 복사해 두게 된다.
 * <p>
 * 반복 확인을 성공으로 넘기지 않는다. 확인은 "내가 지금부터 본다"는 표시라서 두 사람이 눌렀을 때
 * 나중 사람에게도 성공이라고 답하면, 실제로는 앞사람이 맡고 있는 Incident를 자기가 맡았다고 믿게
 * 된다. acknowledgedAt이 첫 확인의 시각으로 남는 것과도 어긋난다.
 * <p>
 * 상태 전이를 거부한 것은 {@link Incident}이며 이 예외는 그 거부를 API가 쓰는 말로 옮긴 것뿐이다.
 * 그래서 거부의 근거는 cause에만 남기고 응답에는 옮기지 않는다.
 */
public class IncidentAcknowledgeNotAllowedException extends RuntimeException {

	public IncidentAcknowledgeNotAllowedException(Long incidentId, Throwable cause) {
		super("Incident acknowledge not allowed: " + incidentId, cause);
	}

}

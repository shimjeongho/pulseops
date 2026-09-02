package com.pulseops.incident.dto;

import com.pulseops.incident.Incident;
import com.pulseops.incident.IncidentSeverity;
import com.pulseops.incident.IncidentStatus;
import com.pulseops.incident.IncidentType;
import java.time.Instant;

/**
 * 처리 중인 Incident 하나를 목록에 한 줄로 보여 주기 위한 형태이다.
 * <p>
 * Incident를 연 창의 관측값은 담지 않는다. 목록에서 답해야 하는 것은 "지금 무엇이 몇 건 열려
 * 있고 무엇부터 봐야 하는가"이며, 요청 수나 오류율은 그 판단을 돕지 않으면서 줄마다 붙어 응답을
 * 키운다. 근거를 보려는 사람은 이미 어느 Incident를 볼지 정한 뒤이므로 그 값은 상세 조회가 맡는다.
 * <p>
 * resolvedAt이 없는 것도 같은 이유가 아니라 정의 때문이다. 이 목록에는 끝나지 않은 Incident만
 * 담기므로 그 자리는 언제나 null이 된다. 항상 비어 있는 열은 읽는 쪽에 아무것도 알려 주지 않는다.
 * <p>
 * acknowledgedAt은 남긴다. status만으로도 확인 여부는 알 수 있지만 언제부터 사람이 붙어 있었는지는
 * 이 값에만 있고, 오래 붙들려 있는 Incident를 목록에서 알아보는 근거가 된다.
 */
public record IncidentSummaryResponse(Long id, String service, String method, String endpoint, IncidentType type,
		IncidentSeverity severity, IncidentStatus status, Instant openedAt, Instant acknowledgedAt) {

	public static IncidentSummaryResponse from(Incident incident) {
		return new IncidentSummaryResponse(incident.getId(), incident.getService(), incident.getMethod(),
				incident.getEndpoint(), incident.getType(), incident.getSeverity(), incident.getStatus(),
				incident.getOpenedAt(), incident.getAcknowledgedAt());
	}

}

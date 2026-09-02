package com.pulseops.incident.dto;

import com.pulseops.incident.Incident;
import com.pulseops.incident.IncidentSeverity;
import com.pulseops.incident.IncidentStatus;
import com.pulseops.incident.IncidentType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Incident 하나를 그것을 연 창의 관측값까지 함께 보여 주기 위한 형태이다.
 * <p>
 * trigger로 시작하는 값들은 Incident가 열린 순간의 관측이며 지금의 값이 아니다. 그래서 창의 경계를
 * 함께 내보낸다. 어느 구간을 본 숫자인지 없이 오류율만 있으면, 읽는 사람은 그것을 현재 값으로 읽고
 * Dashboard의 값과 다른 이유를 찾게 된다.
 * <p>
 * updatedAt은 담지 않는다. 심각도가 언제 올랐는지를 뜻하는 값이지만 무엇이 어떻게 올랐는지는 남지
 * 않아, 시각만 봐서는 무엇도 설명하지 못한다. active_key도 담지 않는다. 활성 Incident의 유일성을
 * 지키려고 DB가 파생시키는 열이며 밖에서는 뜻이 없다.
 * <p>
 * triggerErrorRate는 Incident에 저장된 값을 그대로 내보낸다. 여기서 다시 반올림하면 화면의 숫자와
 * 판정의 근거가 갈라진다. triggerP95LatencyMs는 표본이 없어 구하지 못했으면 null이며 0으로 바꾸지
 * 않는다. 응답 시간을 재지 못한 창이 가장 빠른 창이 된다.
 */
public record IncidentDetailResponse(Long id, String service, String method, String endpoint, IncidentType type,
		IncidentSeverity severity, IncidentStatus status, Instant openedAt, Instant acknowledgedAt, Instant resolvedAt,
		Instant triggerWindowStart, Instant triggerWindowEnd, long triggerRequestCount, long triggerErrorCount,
		BigDecimal triggerErrorRate, Long triggerP95LatencyMs) {

	public static IncidentDetailResponse from(Incident incident) {
		return new IncidentDetailResponse(incident.getId(), incident.getService(), incident.getMethod(),
				incident.getEndpoint(), incident.getType(), incident.getSeverity(), incident.getStatus(),
				incident.getOpenedAt(), incident.getAcknowledgedAt(), incident.getResolvedAt(),
				incident.getTriggerWindowStart(), incident.getTriggerWindowEnd(), incident.getTriggerRequestCount(),
				incident.getTriggerErrorCount(), incident.getTriggerErrorRate(), incident.getTriggerP95LatencyMs());
	}

}

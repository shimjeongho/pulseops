package com.pulseops.analysis;

import com.pulseops.incident.Incident;
import com.pulseops.incident.IncidentSeverity;
import com.pulseops.incident.IncidentStatus;
import com.pulseops.incident.IncidentType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * AI 분석에 실제로 보내는 Incident 사실의 전부이다.
 * <p>
 * Incident Entity를 그대로 보내지 않고 이 record로 좁힌다. 보낼 값을 한 자리에 적어 두어야 무엇이
 * 나가는지 코드를 읽어 확인할 수 있고, Incident에 열이 하나 늘었다는 이유만으로 그것이 조용히 외부
 * API로 흘러가지 않는다.
 * <p>
 * id를 담지 않는다. 저장소가 부여한 번호는 분석에 아무 근거도 되지 않으면서 우리 DB의 내부 사정을
 * 밖으로 내보낸다. 어느 Incident의 분석인지는 저장된 행의 incident_id가 안다.
 * <p>
 * acknowledgedAt과 resolvedAt도 담지 않는다. 사람이 언제 확인했고 언제 닫았는지는 대응의 기록이지
 * 장애의 근거가 아니며, 그 시각을 주면 "빨리 해결됐으니 가벼운 문제였다" 같은 추론이 관측되지 않은
 * 사실로 등장한다. 지금 어느 단계인지만 status로 알려 사후 분석임을 분명히 한다.
 * <p>
 * 여기에 없는 것이 분석의 한계이다. log, stack trace, trace, 배포 이력, 소스 코드, commit은 PulseOps가
 * 지금 어디에도 갖고 있지 않다. 그래서 분석 결과의 limitations에도 그 사실이 남아야 한다.
 */
public record IncidentAnalysisFacts(String service, String method, String endpoint, IncidentType type,
		IncidentSeverity severity, IncidentStatus status, Instant openedAt, Instant triggerWindowStart,
		Instant triggerWindowEnd, long triggerRequestCount, long triggerErrorCount, BigDecimal triggerErrorRate,
		Long triggerP95LatencyMs) {

	/**
	 * 분석 시점의 Incident에서 보낼 값만 뽑는다.
	 * <p>
	 * 이 변환이 곧 snapshot이다. 여기서 만들어진 값은 이후 Incident가 확인되거나 해결되어도 바뀌지
	 * 않으며, 그대로 저장되어 "분석이 무엇을 보고 만들어졌는가"에 답한다.
	 */
	public static IncidentAnalysisFacts from(Incident incident) {
		return new IncidentAnalysisFacts(incident.getService(), incident.getMethod(), incident.getEndpoint(),
				incident.getType(), incident.getSeverity(), incident.getStatus(), incident.getOpenedAt(),
				incident.getTriggerWindowStart(), incident.getTriggerWindowEnd(), incident.getTriggerRequestCount(),
				incident.getTriggerErrorCount(), incident.getTriggerErrorRate(), incident.getTriggerP95LatencyMs());
	}

}

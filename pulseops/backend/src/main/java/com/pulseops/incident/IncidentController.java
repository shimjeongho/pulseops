package com.pulseops.incident;

import com.pulseops.incident.dto.IncidentDetailResponse;
import com.pulseops.incident.dto.IncidentSummaryResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

	private final IncidentService incidentService;

	public IncidentController(IncidentService incidentService) {
		this.incidentService = incidentService;
	}

	/**
	 * 목록은 감싸지 않고 배열 그대로 내보낸다. Metric 응답이 {@code generatedAt}을 함께 담느라 객체로
	 * 감싼 것과 달리, 여기에는 줄들이 공유하는 값이 없다. 담을 것이 없는 껍데기는 읽는 쪽에 자리 하나를
	 * 더 지나가게 할 뿐이다.
	 */
	@GetMapping("/active")
	public List<IncidentSummaryResponse> getActiveIncidents() {
		return this.incidentService.activeIncidents();
	}

	@GetMapping("/{incidentId}")
	public IncidentDetailResponse getIncident(@PathVariable Long incidentId) {
		return this.incidentService.incidentDetail(incidentId);
	}

}

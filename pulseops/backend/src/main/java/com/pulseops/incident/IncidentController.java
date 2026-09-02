package com.pulseops.incident;

import com.pulseops.incident.dto.IncidentDetailResponse;
import com.pulseops.incident.dto.IncidentSummaryResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

	/**
	 * 확인 처리는 상태를 바꾸므로 POST이다. 바꿀 값을 요청이 정하지 않아 본문이 없다. 무엇을 확인
	 * 처리할지는 경로가, 언제 확인했는지는 서버가 정하며, 그 둘 말고 요청이 실어 보낼 것이 없다.
	 * <p>
	 * 갱신된 상세를 그대로 돌려주므로 Client는 확인 직후 다시 조회하지 않는다. 그 다시 조회하는 사이에
	 * Detector가 심각도를 올리거나 Incident를 끝낼 수 있어, 두 번 나눠 읽으면 화면이 어느 시점의
	 * 상태인지 말할 수 없게 된다.
	 */
	@PostMapping("/{incidentId}/acknowledge")
	public IncidentDetailResponse acknowledgeIncident(@PathVariable Long incidentId) {
		return this.incidentService.acknowledge(incidentId);
	}

}

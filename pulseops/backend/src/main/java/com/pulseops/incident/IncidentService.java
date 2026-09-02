package com.pulseops.incident;

import com.pulseops.incident.dto.IncidentDetailResponse;
import com.pulseops.incident.dto.IncidentSummaryResponse;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 사람이 Incident를 읽는 두 가지 방법인 처리 중인 목록과 하나의 상세를 제공한다.
 * <p>
 * 무엇이 처리 중인지는 여기서 정하지 않고 {@link IncidentRepository#findActive()}의 정의를 그대로
 * 쓴다. 상태 목록을 이 자리에서 다시 적으면 Detection이 보는 "처리 중"과 화면이 보는 "처리 중"이
 * 서로 다른 값이 될 수 있고, 그러면 화면에 없는 Incident가 새 Incident를 막는다.
 * <p>
 * 반대로 순서는 전부 여기서 정한다. 정렬 기준에 심각도가 들어가는데 심각도는 DB에 문자열로 저장되어
 * 있어, 저장소에 맡기면 이름의 사전순이 곧 심각도의 순서인 것처럼 동작한다. 지금은 CRITICAL이
 * WARNING보다 우연히 앞설 뿐이며 값이 하나만 늘어도 조용히 어긋난다.
 */
@Service
public class IncidentService {

	/**
	 * 심한 것이 먼저, 같은 심각도면 최근에 열린 것이 먼저 온다. 목록의 첫 줄이 지금 가장 급한
	 * Incident여야 하고, 같은 무게라면 방금 일어난 일이 먼저 눈에 들어와야 한다.
	 * <p>
	 * 그 뒤로 계열과 종류까지 비교해 순서를 완전히 결정한다. openedAt은 판정에 쓴 창의 끝이라 같은
	 * 순간에 닫힌 여러 계열의 Incident가 같은 값을 갖는 것이 드문 일이 아니고, 거기서 순서가 정해지지
	 * 않으면 저장소가 돌려준 순서가 곧 화면의 순서가 된다. 그 순서는 보장된 것이 아니므로 몇 초마다
	 * 다시 읽는 화면에서 줄의 위치가 이유 없이 바뀔 수 있다.
	 * <p>
	 * 마지막 기준을 계열과 종류로 삼는 이유는 그 넷이 처리 중인 Incident를 하나로 정하는 값이기
	 * 때문이다. {@code uk_incident_active}가 그 조합에 UNIQUE를 걸어 두므로 여기까지 오면 같은 값이
	 * 둘일 수 없고, 순서가 완전히 정해진다.
	 */
	private static final Comparator<IncidentSummaryResponse> ACTIVE_ORDER = Comparator
		.comparing(IncidentSummaryResponse::severity, IncidentService::compareBySeverityDescending)
		.thenComparing(IncidentSummaryResponse::openedAt, Comparator.reverseOrder())
		.thenComparing(IncidentSummaryResponse::service)
		.thenComparing(IncidentSummaryResponse::endpoint)
		.thenComparing(IncidentSummaryResponse::method)
		.thenComparing(IncidentSummaryResponse::type);

	private final IncidentRepository incidentRepository;

	public IncidentService(IncidentRepository incidentRepository) {
		this.incidentRepository = incidentRepository;
	}

	/**
	 * 처리 중인 Incident 전부를 봐야 할 순서로 돌려준다.
	 * <p>
	 * 상태로 거르는 일과 순서를 정하는 일을 화면에 넘기지 않는다. 넘기면 무엇이 처리 중인가라는
	 * Domain의 정의가 화면 수만큼 복사되고, 그 중 하나만 어긋나도 다른 목록이 만들어진다.
	 * <p>
	 * 비어 있는 목록은 정상이다. 처리 중인 Incident가 없는 것은 시스템이 건강하다는 뜻이며 실패가
	 * 아니다.
	 */
	public List<IncidentSummaryResponse> activeIncidents() {
		return this.incidentRepository.findActive()
			.stream()
			.map(IncidentSummaryResponse::from)
			.sorted(ACTIVE_ORDER)
			.toList();
	}

	/**
	 * Incident 하나를 그것을 연 창의 관측값까지 함께 돌려준다.
	 * <p>
	 * 상태를 보지 않는다. 끝난 Incident의 기록을 읽는 것이야말로 나중에 무슨 일이 있었는지 돌아보는
	 * 방법이고, 목록에서 사라졌다는 이유로 상세까지 닫히면 그 기록에 닿을 방법이 없어진다.
	 */
	public IncidentDetailResponse incidentDetail(Long incidentId) {
		return this.incidentRepository.findById(incidentId)
			.map(IncidentDetailResponse::from)
			.orElseThrow(() -> new IncidentNotFoundException(incidentId));
	}

	/**
	 * 심각도의 순서는 {@link IncidentSeverity#isHigherThan}만 근거로 삼는다. 여기서 선언 순서나 이름을
	 * 직접 비교하면 심각도의 순서를 아는 자리가 둘이 되고, 값이 늘어날 때 한쪽만 고쳐진다.
	 */
	private static int compareBySeverityDescending(IncidentSeverity left, IncidentSeverity right) {
		if (left == right) {
			return 0;
		}
		return left.isHigherThan(right) ? -1 : 1;
	}

}

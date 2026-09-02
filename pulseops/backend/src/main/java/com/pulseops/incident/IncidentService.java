package com.pulseops.incident;

import com.pulseops.incident.dto.IncidentDetailResponse;
import com.pulseops.incident.dto.IncidentSummaryResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사람이 Incident를 읽는 두 가지 방법인 처리 중인 목록과 하나의 상세를 제공하고, 하나를 확인
 * 처리한다.
 * <p>
 * 읽기만 하는 두 method에는 Transaction을 두지 않고 확인 처리에만 둔다. 앞의 둘은 저장소 호출이
 * 한 번뿐이고 Incident에 지연 로딩되는 연관도 없어 경계가 지킬 것이 없지만, 확인 처리는 잠그고
 * 읽은 뒤 그 상태를 바꾸는 일이라 그 사이가 하나의 Transaction이어야 한다. 잠금은 Transaction이
 * 끝날 때까지만 유지되므로, 경계가 없으면 잠근 직후 풀린 채로 상태를 바꾸게 된다.
 * <p>
 * {@link IncidentDetector}처럼 TransactionTemplate을 쓰지 않는다. 그쪽이 그 방식을 쓰는 이유는 commit
 * 이 끝난 바깥에서 저장소 실패를 붙잡아 Kafka 소비를 계속하기 위해서인데, 확인 처리는 실패를
 * 삼키면 안 되고 그대로 요청자에게 알려야 한다. 삼킬 것이 없으면 경계를 손으로 여닫을 이유도 없다.
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
	 * Incident에 사람이 보고 있다는 표시를 남기고 갱신된 상태를 돌려준다.
	 * <p>
	 * 잠그고 읽는다. 확인 처리는 읽은 상태를 근거로 상태를 바꾸는 일이라, 잠그지 않으면 두 요청이
	 * 나란히 OPEN을 보고 둘 다 성공해 나중 요청이 앞 요청의 확인 시각을 덮어쓴다.
	 * <p>
	 * 잠그는 것은 Incident 행 하나뿐이다. {@link IncidentDetector}는 DetectionState를 잠근 뒤 Incident를
	 * 잠그는데, 여기서 Incident를 잡은 채로 DetectionState를 요청하면 두 흐름이 서로가 쥔 것을
	 * 기다리며 맞물려 멈춘다. 확인 처리는 판정의 연속 횟수나 cursor와 아무 관계가 없으므로 그 상태를
	 * 볼 이유도 없다.
	 * <p>
	 * 그래도 Incident 행 하나를 두고는 판정과 부딪힐 수 있다. 이쪽은 ID로 찾아 기본 Index를 먼저 잠근
	 * 뒤 status를 바꾸며 {@code idx_incident_series}를 잠그는데, 판정은 계열로 찾아 그 반대 순서로
	 * 잠근다. 같은 행을 서로 다른 Index 순서로 잠그므로 MySQL이 한쪽을 교착으로 물릴 수 있다.
	 * <p>
	 * 물린 쪽은 통째로 되돌아가며 절반만 반영된 상태를 남기지 않는다. 그래서 상대의 확인이나 회복,
	 * 악화가 이 경쟁 때문에 사라지지는 않는다. 판정이 물리면 창을 어디까지 보았는지도 함께 되돌아가
	 * 같은 창을 다시 판정하고, 확인이 물리면 요청자가 다시 누르면 된다. 그 대가는 유실이 아니라
	 * 가끔 나가는 실패 응답이며, 그것을 없애려면 두 흐름이 같은 Index 순서로 잠그도록 조회를 맞춰야
	 * 한다. 지금은 잠금 방식을 단순하게 두고 그 성질을 여기에 적어 둔다.
	 * <p>
	 * 상태 전이를 스스로 판단하지 않는다. 확인 처리가 가능한 상태인지 아는 곳은 {@link Incident}
	 * 하나여야 하고, 여기서 상태를 미리 검사하면 같은 규칙이 두 곳에 생겨 한쪽만 바뀔 수 있다.
	 * 그래서 전이를 그대로 시도하고, 그 한 호출이 거부한 경우만 API의 말로 옮긴다. Domain이 던지는
	 * Type을 그대로 밖으로 흘리면 Web 계층이 뜻이 정해지지 않은 실패까지 같은 상태로 답하게 된다.
	 * <p>
	 * 바뀐 값을 따로 저장하지 않는다. 잠그고 읽은 Incident는 Transaction이 관리하는 상태이므로 commit
	 * 시점에 변경이 그대로 반영된다. 여기서 save를 부르면 이미 반영될 변경을 한 번 더 지시하는 것뿐이다.
	 * <p>
	 * 응답은 상세 조회와 같은 형태이다. 확인 직후의 화면은 그 Incident의 최신 상태를 그려야 하는데,
	 * 그 사이 Detector가 심각도를 올렸을 수도 있어 확인 결과만 돌려주면 화면이 낡은 값을 그린다.
	 * 같은 Transaction에서 만든 이 응답이 그 시점의 확정된 상태이다.
	 */
	@Transactional
	public IncidentDetailResponse acknowledge(Long incidentId) {
		Incident incident = this.incidentRepository.findByIdForUpdate(incidentId)
			.orElseThrow(() -> new IncidentNotFoundException(incidentId));
		try {
			incident.acknowledge(Instant.now());
		}
		catch (IllegalStateException ex) {
			throw new IncidentAcknowledgeNotAllowedException(incidentId, ex);
		}
		return IncidentDetailResponse.from(incident);
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

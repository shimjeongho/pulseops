package com.pulseops.incident;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Incident의 영속화를 담당한다.
 * <p>
 * 조회는 무엇을 하려고 찾는가로 나뉜다. Detection은 한 계열의 한 종류를 바꾸려고, 확인 처리는 ID로
 * 지목한 하나를 바꾸려고, 화면은 전부를 보여 주려고 찾는다. 바꾸려는 쪽만 잠근다.
 * <p>
 * 잠그는 조회는 {@link #findByIdForUpdate} 하나뿐이다. 계열로 찾든 ID로 찾든 마지막에는 같은 자물쇠를
 * 잡는다. Incident를 바꾸는 흐름이 서로 다른 방식으로 잠그기 시작하면 잠금 순서가 갈라지고, 그때부터는
 * 어느 두 흐름이 맞물리는지 코드만 보고 말할 수 없게 된다.
 * <p>
 * Detection이 계열별 조회를 쓰는 이유는 이상이 연속됐을 때 새로 열지 기존 기록의 심각도를 올릴지를
 * 정해야 하고, 회복됐을 때는 끝낼 대상을 찾아야 하기 때문이다. 두 경우 모두 필요한 것이 같은
 * 조회라서 하나로 둔다.
 */
public interface IncidentRepository extends JpaRepository<Incident, Long> {

	/**
	 * 한 계열의 한 종류에 처리 중인 Incident를 잠그고 읽는다.
	 * <p>
	 * 처리 중이라는 것은 OPEN이거나 ACKNOWLEDGED라는 뜻이다. 사람이 확인했다고 해서 장애가 끝난 것은
	 * 아니므로 ACKNOWLEDGED도 그 자리를 차지한다. 이 조건은 {@code active_key}가 값을 갖는 조건과
	 * 같아야 하며, 어긋나면 UNIQUE가 막는 자리와 Application이 보는 자리가 달라진다.
	 * <p>
	 * RESOLVED는 제외한다. 끝난 기록은 이력이고 같은 계열에 얼마든지 쌓인다. 그것이 걸려 나오면 같은
	 * 문제가 다시 났을 때 새 Incident를 열지 못하고 끝난 기록을 되살리게 된다.
	 * <p>
	 * 잠글 행을 계열로 곧장 찾지 않고 ID를 먼저 알아낸 뒤 {@link #findByIdForUpdate}로 잠근다. 계열
	 * 조회는 {@code idx_incident_series}의 마지막 열인 status를 목록으로 받아 range scan이 되고,
	 * REPEATABLE READ에서 range를 잠그면 record뿐 아니라 그 앞의 gap까지 잠근다. gap 잠금은 서로
	 * 호환되므로 두 처리가 나란히 같은 gap을 쥘 수 있고, 이어서 status를 바꾸는 UPDATE가 그 gap 안에서
	 * index 항목을 옮기려 할 때 서로를 기다려 교착이 된다. 같은 조회를 쓰는 두 흐름 사이에서도 일어난다.
	 * <p>
	 * ID로 잠그면 PK를 정확히 하나 짚는 조회라 gap을 잡지 않고 그 행만 잠근다. 그래서 Incident를 바꾸는
	 * 모든 흐름이 언제나 같은 자물쇠 하나를 먼저 잡게 되고, 서로를 기다리는 고리가 만들어지지 않는다.
	 * <p>
	 * ID를 찾는 조회는 잠그지 않는다. 잠그지 않는 읽기는 어떤 자물쇠도 잡지 않아 교착에 끼어들지 않는다.
	 * 대신 그 결과는 읽은 순간의 것이므로, 잠근 뒤에 그 행이 여전히 이 계열의 처리 중인 Incident가 맞는지
	 * 다시 확인한다. 잠금을 기다리는 사이에 다른 처리가 그 Incident를 끝냈거나 그 자리를 다른 Incident가
	 * 넘겨받았을 수 있다.
	 * <p>
	 * 조건이 달라졌으면 비어 있는 결과를 돌려준다. 부르는 쪽에게 "지금 이 계열에 잠글 수 있는 처리 중인
	 * Incident가 없다"는 뜻이며, 이는 정말로 없을 때와 같은 답이다. 그 사이 새로 열린 Incident를 대신
	 * 돌려주지 않는 것은, 부르는 쪽이 방금 판정한 창을 근거로 그 Incident를 바꾸려 하는데 그 근거가
	 * 새 Incident에는 해당하지 않기 때문이다.
	 */
	default Optional<Incident> findActiveForUpdate(String service, String method, String endpoint,
			IncidentType type) {
		return findActiveId(service, method, endpoint, type).flatMap(this::findByIdForUpdate)
			.filter((incident) -> isStillActiveFor(incident, service, method, endpoint, type));
	}

	/**
	 * 잠글 Incident의 ID만 찾는다. 잠그지 않으며 Entity도 만들지 않는다.
	 * <p>
	 * Entity를 만들지 않는 것이 중요하다. 여기서 Incident를 Entity로 읽어 두면 뒤따르는 잠금 조회가
	 * DB에서 새로 읽은 행 대신 이미 읽어 둔 것을 돌려주어, 잠금은 걸렸는데 값은 낡은 상태가 된다.
	 * 그대로 저장하면 그 사이 다른 처리가 남긴 변경이 조용히 사라진다.
	 * <p>
	 * 처리 중의 정의는 {@link #findActive}와 같아야 한다. 어느 하나만 바뀌면 화면에 보이는 Incident와
	 * 판정이 잠그는 Incident가 달라진다.
	 */
	@Query("""
			select incident.id
			from Incident incident
			where incident.service = :service
				and incident.method = :method
				and incident.endpoint = :endpoint
				and incident.type = :type
				and incident.status in (
					com.pulseops.incident.IncidentStatus.OPEN,
					com.pulseops.incident.IncidentStatus.ACKNOWLEDGED
				)
			""")
	Optional<Long> findActiveId(@Param("service") String service, @Param("method") String method,
			@Param("endpoint") String endpoint, @Param("type") IncidentType type);

	/**
	 * 잠근 행이 여전히 찾던 그것인지 확인한다.
	 * <p>
	 * 상태 목록을 여기에 다시 적는 것은 {@link #findActiveId}의 조건을 잠금 뒤에 한 번 더 확인하기
	 * 위해서이다. 두 곳이 어긋나면 잠금 전에는 처리 중이라 보고 잠금 뒤에는 아니라고 보게 되므로,
	 * 한쪽을 고칠 때 다른 쪽도 함께 고쳐야 한다.
	 * <p>
	 * 계열과 종류까지 확인한다. ID는 잠금을 기다리는 사이에 끝난 Incident의 것일 수 있고, 그 자리를
	 * 다른 계열의 Incident가 차지하는 일은 없지만 확인 대상을 상태 하나로 좁히면 무엇을 잠갔는지
	 * 결과만 보고는 알 수 없다.
	 */
	private static boolean isStillActiveFor(Incident incident, String service, String method, String endpoint,
			IncidentType type) {
		return incident.getService().equals(service) && incident.getMethod().equals(method)
				&& incident.getEndpoint().equals(endpoint) && incident.getType() == type
				&& (incident.getStatus() == IncidentStatus.OPEN
						|| incident.getStatus() == IncidentStatus.ACKNOWLEDGED);
	}

	/**
	 * Incident 하나를 잠그고 읽는다.
	 * <p>
	 * 확인 처리처럼 읽은 결과로 상태를 바꾸는 자리에서 쓴다. 잠그지 않으면 같은 Incident에 대한 두
	 * 요청이 나란히 OPEN을 보고 둘 다 확인 처리로 넘어가, 나중 요청이 앞 요청의 acknowledgedAt을
	 * 덮어쓴다.
	 * <p>
	 * 상태를 조건에 넣지 않고 ID만으로 찾는다. 무엇이 확인 처리 가능한 상태인가는 {@link Incident}가
	 * 아는 규칙이고, 조회가 그것을 함께 판단하면 규칙이 두 곳에 생긴다. 게다가 상태로 걸러 버리면
	 * 없는 Incident와 확인할 수 없는 상태의 Incident가 똑같이 "없음"으로 돌아와, 404와 409를 나눌
	 * 근거가 사라진다.
	 * <p>
	 * Incident를 잠그는 자리는 여기 하나뿐이다. {@link #findActiveForUpdate}도 계열로 ID를 알아낸 뒤
	 * 결국 이 조회로 잠근다. PK를 정확히 하나 짚으므로 gap을 잡지 않고 그 행만 잠그며, 모든 흐름이
	 * 같은 자물쇠를 같은 방식으로 잡는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select incident
			from Incident incident
			where incident.id = :incidentId
			""")
	Optional<Incident> findByIdForUpdate(@Param("incidentId") Long incidentId);

	/**
	 * 처리 중인 Incident를 전부 읽는다.
	 * <p>
	 * 무엇이 처리 중인가는 {@link #findActiveForUpdate}와 같은 정의를 쓴다. 두 조회가 서로 다른
	 * 상태 목록을 보면, 화면에 없는 Incident 때문에 새 Incident가 열리지 않는 상황이 생긴다.
	 * <p>
	 * 잠그지 않는다. 부르는 쪽이 읽은 결과로 아무것도 바꾸지 않기 때문이다. 목록을 만드는 조회가
	 * 행을 잠그면 화면을 여는 것만으로 Detection이 그 행을 기다리게 된다.
	 * <p>
	 * 순서를 정하지 않는다. 무엇을 먼저 보여 줄지는 저장이 아니라 화면의 판단이고, 그 기준에 심각도의
	 * 순서가 들어간다. 심각도는 문자열로 저장되므로 DB에 맡기면 이름의 사전순이 심각도의 순서인
	 * 것처럼 동작하다가 값이 늘어나는 순간 조용히 어긋난다. 그래서 정렬은 {@link IncidentService}가
	 * 전부 맡는다.
	 */
	@Query("""
			select incident
			from Incident incident
			where incident.status in (
				com.pulseops.incident.IncidentStatus.OPEN,
				com.pulseops.incident.IncidentStatus.ACKNOWLEDGED
			)
			""")
	List<Incident> findActive();

}

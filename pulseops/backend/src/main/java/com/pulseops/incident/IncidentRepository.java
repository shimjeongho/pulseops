package com.pulseops.incident;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Incident의 영속화를 담당한다.
 * <p>
 * 조회는 처리 중인 Incident를 찾는 것 하나뿐이다. Detection은 이상이 연속됐을 때 새로 열지 기존
 * 기록의 심각도를 올릴지를 정해야 하고, 회복됐을 때는 끝낼 대상을 찾아야 한다. 두 경우 모두 필요한
 * 것이 같은 조회라서 하나로 둔다.
 */
public interface IncidentRepository extends JpaRepository<Incident, Long> {

	/**
	 * 한 계열의 한 종류에 처리 중인 Incident를 잠그고 읽는다.
	 * <p>
	 * 잠그는 이유는 이 조회를 부르는 쪽이 언제나 읽은 결과로 무언가를 바꾸기 때문이다. 없으면 새로
	 * 열고 있으면 심각도를 올리거나 끝낸다. 잠그지 않으면 두 처리가 나란히 "없다"를 보고 각자 열려
	 * 하다가 {@code uk_incident_active}에 걸려, 정상 흐름에서 제약 위반이 일어난다.
	 * <p>
	 * 처리 중이라는 것은 OPEN이거나 ACKNOWLEDGED라는 뜻이다. 사람이 확인했다고 해서 장애가 끝난 것은
	 * 아니므로 ACKNOWLEDGED도 그 자리를 차지한다. 이 조건은 {@code active_key}가 값을 갖는 조건과
	 * 같아야 하며, 어긋나면 UNIQUE가 막는 자리와 Application이 보는 자리가 달라진다.
	 * <p>
	 * RESOLVED는 제외한다. 끝난 기록은 이력이고 같은 계열에 얼마든지 쌓인다. 그것이 걸려 나오면 같은
	 * 문제가 다시 났을 때 새 Incident를 열지 못하고 끝난 기록을 되살리게 된다.
	 * <p>
	 * 상태 목록을 인자로 받지 않는다. 무엇이 처리 중인가는 부르는 쪽마다 다를 수 있는 선택이 아니라
	 * 이 Domain의 정의이고, 인자로 열면 그 정의가 부르는 자리 수만큼 복사된다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select incident
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
	Optional<Incident> findActiveForUpdate(@Param("service") String service, @Param("method") String method,
			@Param("endpoint") String endpoint, @Param("type") IncidentType type);

}

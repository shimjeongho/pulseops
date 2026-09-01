package com.pulseops.incident;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Detection State의 영속화를 담당한다.
 * <p>
 * 조회는 identity 하나뿐이며 잠금을 건다. 이 상태를 읽는 쪽은 언제나 읽은 값을 바탕으로 연속 횟수를
 * 고쳐 쓰므로, 잠그지 않고 읽으면 같은 계열의 두 처리가 같은 값을 읽어 한쪽의 창이 통째로 사라진다.
 * 잠그지 않는 조회를 함께 두지 않는 것은 그것을 쓰는 자리가 곧 그런 유실 지점이 되기 때문이다.
 * <p>
 * 이 잠금은 이미 있는 행에만 걸린다. 행이 없을 때는 잠글 대상이 없어 두 처리가 나란히 없음을 확인하고
 * 각자 insert할 수 있으며, 그것을 막는 것은 {@code uk_detection_state_series_type}이다. 그 충돌을
 * 어떻게 다룰지는 이 조회를 부르는 쪽에서 정한다.
 */
public interface DetectionStateRepository extends JpaRepository<DetectionState, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select state
			from DetectionState state
			where state.service = :service
				and state.method = :method
				and state.endpoint = :endpoint
				and state.type = :type
			""")
	Optional<DetectionState> findForUpdate(@Param("service") String service, @Param("method") String method,
			@Param("endpoint") String endpoint, @Param("type") IncidentType type);

}

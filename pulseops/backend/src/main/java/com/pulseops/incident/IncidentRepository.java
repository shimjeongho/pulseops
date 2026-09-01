package com.pulseops.incident;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Incident의 영속화를 담당한다.
 * <p>
 * 조회 method를 미리 만들지 않는다. 활성 Incident를 잠그고 찾는 조회는 그것을 실제로 쓰는 Detection이
 * 들어올 때 함께 추가한다. 쓰이지 않는 조회는 어떤 잠금과 어떤 조건이 필요한지 근거 없이 정해 두는
 * 것이라, 나중에 그 근거가 생겼을 때 오히려 걸림돌이 된다.
 */
public interface IncidentRepository extends JpaRepository<Incident, Long> {

}

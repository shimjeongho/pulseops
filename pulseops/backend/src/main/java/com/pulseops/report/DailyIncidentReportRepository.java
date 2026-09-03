package com.pulseops.report;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Daily Incident Report의 영속화를 담당한다.
 * <p>
 * 잠그는 조회가 없다. 이 기록은 한 번 만들어지면 바뀌지 않으므로 읽은 값을 근거로 무엇을 바꾸는
 * 흐름 자체가 없고, 같은 날짜를 두 번 만들려는 경쟁은 {@code report_date}의 UNIQUE가 막는다.
 * 잠금으로 막으려 들면 아직 없는 행을 잠가야 하는데, 그것은 없는 행 앞의 범위를 잠그는 일이라
 * 실시간 판정이 Incident를 다루는 잠금과 얽힐 수 있다.
 */
public interface DailyIncidentReportRepository extends JpaRepository<DailyIncidentReport, Long> {

	/**
	 * 날짜로 하나를 찾는다. Report를 밖에서 가리키는 값이 날짜이므로 조회도 그것으로 한다. UNIQUE가
	 * 걸려 있어 결과는 언제나 하나이거나 없다.
	 */
	Optional<DailyIncidentReport> findByReportDate(LocalDate reportDate);

	/**
	 * 최근 Report부터 순서대로 읽는다. 몇 개까지 읽을지는 부르는 쪽이 정한다.
	 * <p>
	 * 개수를 method 이름에 넣지 않는다. 그렇게 하면 그 수가 이름의 일부가 되어 화면이 필요로 하는
	 * 개수를 바꿀 때마다 method 이름이 바뀌고, 그 수를 아는 테스트가 같은 숫자를 따로 적게 된다.
	 */
	List<DailyIncidentReport> findAllByOrderByReportDateDesc(Limit limit);

}

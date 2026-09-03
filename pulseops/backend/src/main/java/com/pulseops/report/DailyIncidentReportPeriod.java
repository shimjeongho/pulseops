package com.pulseops.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Daily Incident Report 하나가 집계하는 시간 구간이다.
 * <p>
 * 날짜 하나만 들고 구간의 두 끝을 스스로 만든다. 시작과 끝을 밖에서 받으면 날짜와 어긋난 구간을
 * 가진 Report를 만들 수 있고, 그러면 {@code reportDate}가 무엇을 센 것인지 말하지 못한다. 여기서
 * 파생시키면 시작이 끝보다 뒤인 구간 자체가 존재할 수 없어 그 검증도 필요 없다.
 * <p>
 * 구간은 {@code [start, end)}이다. 끝을 포함하면 자정 정각에 일어난 일이 이틀치 Report에 모두
 * 세어진다. 하루의 마지막 순간은 다음 날 자정 직전까지이며 자정 정각은 다음 날의 첫 순간이다.
 * <p>
 * 시간대는 UTC이다. 이 Application은 Incident의 모든 시각을 Instant로 다루고 JDBC와 Hibernate까지
 * UTC로 고정해 두었으므로, 저장된 값의 기준이 이미 UTC이다. Report만 다른 시간대로 하루를 자르면
 * 저장된 시각과 집계 기준이 갈라져, 같은 Incident가 어느 날짜에 속하는지 두 가지 답을 갖는다.
 * 특정 지역 시간대를 쓸 이유가 생기면 그때 이 한 곳만 바꾼다.
 * <p>
 * 시간대를 아는 곳은 여기 하나이다. Scheduler가 언제 도는지와 하루를 어디서 자르는지가 서로 다른
 * 기준을 쓰면, 실행 시각과 집계 구간이 어긋나 아직 끝나지 않은 날짜를 집계하게 된다.
 */
public record DailyIncidentReportPeriod(LocalDate reportDate) {

	/**
	 * {@code @Scheduled}의 zone 속성은 compile 시점 상수만 받으므로 시간대를 문자열로도 둔다.
	 * {@link #REPORT_ZONE}이 이 값으로 만들어지므로 둘이 갈라지지 않는다.
	 */
	public static final String ZONE_ID = "UTC";

	private static final ZoneId REPORT_ZONE = ZoneId.of(ZONE_ID);

	/**
	 * {@code now} 기준으로 완전히 끝난 마지막 날짜이다.
	 * <p>
	 * 오늘을 집계하지 않는다. 아직 끝나지 않은 날짜의 Report는 만들어진 순간부터 사실과 달라지고,
	 * 한 번 만들면 다시 만들지 않는 이 Report에서는 그 어긋남이 영구히 남는다.
	 */
	public static LocalDate lastCompletedDate(Instant now) {
		return LocalDate.ofInstant(now, REPORT_ZONE).minusDays(1);
	}

	public Instant start() {
		return this.reportDate.atStartOfDay(REPORT_ZONE).toInstant();
	}

	/**
	 * 하루 뒤의 자정으로 구한다. 24시간을 더하지 않는 것은 시간대가 UTC가 아니게 되는 날 하루가
	 * 언제나 24시간이라는 가정이 깨지기 때문이다.
	 */
	public Instant end() {
		return this.reportDate.plusDays(1).atStartOfDay(REPORT_ZONE).toInstant();
	}

}

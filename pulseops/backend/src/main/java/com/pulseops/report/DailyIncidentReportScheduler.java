package com.pulseops.report;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 하루에 한 번 전날의 Daily Incident Report를 만든다.
 * <p>
 * 이 Application에서 Spring Scheduler를 쓰는 첫 자리이며, 시간이 되면 해야 하는 일에만 쓴다. 이상을
 * 탐지하는 일은 여전히 Kafka Event가 도착할 때 일어난다. 탐지를 주기 실행으로 옮기면 장애를 알아채기까지
 * 걸리는 시간이 Event의 도착이 아니라 Scheduler 주기에 묶여, 다음 실행까지 아무도 모르는 구간이 생긴다.
 * <p>
 * SSE 연결 신호는 여전히 자기 Thread에서 돈다. 그쪽은 20초마다 도는 연결 유지 신호라 Report 집계와
 * 같은 Scheduler를 나눠 쓰면 집계가 오래 걸리는 날 신호가 함께 밀린다.
 * <p>
 * 완전히 끝난 전날만 집계한다. 아직 끝나지 않은 오늘을 집계하면 만들어진 순간부터 사실과 다른 Report가
 * 되고, 한 번 만들면 다시 만들지 않으므로 그 어긋남이 영구히 남는다.
 * <p>
 * 자정 정각이 아니라 조금 뒤에 돈다. 5분의 여유는 두 가지를 위한 것이다. 날짜가 바뀌는 순간과 집계
 * 시각이 겹치지 않게 하고, 자정 직전에 발생해 Kafka를 지나오는 중인 Event가 Incident로 반영될 시간을
 * 준다. 다만 이 여유가 늦게 도착하는 모든 Event를 보장하지는 않는다. Consumer의 재시도가 길어지거나
 * DLT로 빠진 record는 이 Report에 반영되지 않으며, 그때 그 Incident는 열린 날짜가 아니라 실제로 열린
 * 시각의 Report에 세어져야 했으므로 그만큼 어긋난 채로 남는다.
 * <p>
 * 집계를 여기서 하지 않는다. 이 Class가 하는 일은 어느 날짜를 만들지 정하고, 부르고, 무슨 일이
 * 있었는지 남기는 것뿐이다. 집계까지 여기에 두면 그것을 확인하려면 시간이 지나기를 기다려야 한다.
 */
@Component
public class DailyIncidentReportScheduler {

	private static final Logger logger = LoggerFactory.getLogger(DailyIncidentReportScheduler.class);

	private final DailyIncidentReportService dailyIncidentReportService;

	public DailyIncidentReportScheduler(DailyIncidentReportService dailyIncidentReportService) {
		this.dailyIncidentReportService = dailyIncidentReportService;
	}

	/**
	 * 매일 00:05에 돈다. 시간대는 Report가 하루를 자르는 기준과 같아야 하므로 그 기준을 그대로 쓴다.
	 * 실행 시각과 집계 구간이 서로 다른 시간대를 쓰면 아직 끝나지 않은 날짜를 집계하는 날이 생긴다.
	 * <p>
	 * 기동 시점에는 돌지 않는다. 이 annotation은 cron이 가리키는 시각에만 실행하며, Application이 뜰
	 * 때마다 Report를 만들면 배포가 잦은 날에는 같은 날짜를 몇 번이고 다시 만들려 들게 된다.
	 * <p>
	 * 시각은 여기서 한 번만 읽는다. 어느 날짜를 집계할지와 언제 만들었는지가 같은 순간을 가리켜야 하고,
	 * 시각을 받는 쪽을 따로 두어야 테스트가 날짜 경계를 정할 수 있다.
	 */
	@Scheduled(cron = "0 5 0 * * *", zone = DailyIncidentReportPeriod.ZONE_ID)
	void generateLastCompletedDateReport() {
		generateLastCompletedDateReport(Instant.now());
	}

	/**
	 * 실패를 삼키지 않고 남긴 뒤 끝낸다. 다시 던져도 다음 실행을 막지는 않지만, 그러면 이 자리가 무엇을
	 * 하려다 실패했는지는 Spring의 일반적인 오류 log에만 남는다. 어느 날짜를 만들려 했는지가 함께 남아야
	 * 사람이 그 날짜의 Report가 비어 있는 이유를 알 수 있다.
	 * <p>
	 * 실패한 날짜를 여기서 다시 시도하지 않는다. Report 생성은 같은 날짜로 몇 번을 불러도 안전하므로
	 * 다시 부르는 것 자체는 문제가 아니지만, 몇 번을 언제 다시 부를지 정하는 순간 이 Class가 재시도
	 * 정책을 갖게 된다. 지금 필요한 것은 그것이 아니라 실패가 조용히 성공으로 보이지 않는 것이다.
	 * 그 날짜의 Report가 없다는 사실은 목록에서 빈 자리로 드러난다.
	 */
	void generateLastCompletedDateReport(Instant now) {
		LocalDate reportDate = DailyIncidentReportPeriod.lastCompletedDate(now);
		long startedAt = System.nanoTime();
		try {
			DailyIncidentReportGenerationResult result = this.dailyIncidentReportService.generate(reportDate, now);
			logger.info("Daily Incident Report 생성을 마쳤다. reportDate={} created={} reportId={} elapsedMs={}",
					reportDate, result.created(), result.report().getId(), elapsedMillis(startedAt));
		}
		catch (RuntimeException ex) {
			logger.error("Daily Incident Report를 만들지 못했다. reportDate={} elapsedMs={}", reportDate,
					elapsedMillis(startedAt), ex);
		}
	}

	private static long elapsedMillis(long startedAt) {
		return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
	}

}

package com.pulseops.report;

import com.pulseops.report.dto.DailyIncidentReportResponse;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 만들어진 Daily Incident Report를 읽는다.
 * <p>
 * 생성 Endpoint가 없다. Report를 만드는 것은 Scheduler 하나이며, 그것을 API로 열면 인증 없는 요청이
 * DB 집계를 아무 때나 실행할 수 있게 된다. 읽는 것과 만드는 것을 같은 곳에 두지 않는다.
 */
@RestController
@RequestMapping("/api/reports/daily")
public class DailyIncidentReportController {

	private final DailyIncidentReportService dailyIncidentReportService;

	public DailyIncidentReportController(DailyIncidentReportService dailyIncidentReportService) {
		this.dailyIncidentReportService = dailyIncidentReportService;
	}

	/**
	 * 목록은 감싸지 않고 배열 그대로 내보낸다. 줄들이 공유하는 값이 없어 담을 것이 없는 껍데기가 된다.
	 * Incident 목록과 같은 판단이다.
	 */
	@GetMapping
	public List<DailyIncidentReportResponse> getRecentReports() {
		return this.dailyIncidentReportService.recentReports();
	}

	/**
	 * 날짜는 경로에 {@code YYYY-MM-DD}로 온다. 형식을 annotation으로 못 박는 것은 이것이 API 계약이기
	 * 때문이다. 기본 변환에 맡기면 설정 하나로 받아들이는 형식이 바뀌어도 코드에는 아무 흔적이 없다.
	 * <p>
	 * 형식이 맞지 않으면 Spring이 변환 단계에서 거절하고 {@code GlobalExceptionHandler}가 400으로
	 * 옮긴다. 여기서 문자열을 받아 직접 parsing하면 같은 판단이 두 곳에 생긴다.
	 */
	@GetMapping("/{reportDate}")
	public DailyIncidentReportResponse getReport(
			@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportDate) {
		return this.dailyIncidentReportService.report(reportDate);
	}

}

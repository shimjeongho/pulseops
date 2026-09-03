package com.pulseops.report;

import com.pulseops.incident.IncidentRepository;
import com.pulseops.incident.IncidentResolution;
import com.pulseops.incident.IncidentType;
import com.pulseops.report.dto.DailyIncidentReportResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 끝난 하루의 Incident를 세어 Daily Incident Report를 만들고, 만들어진 Report를 읽는다.
 * <p>
 * 실시간 판정과 완전히 분리된 흐름이다. Incident를 여는 것은 Kafka Event가 들어올 때 도는
 * {@code IncidentDetector}이고, 여기서는 이미 남아 있는 Incident를 읽기만 한다. 이 Class는 Incident를
 * 만들지도 바꾸지도 않으며 Redis도 보지 않는다. Scheduler가 이상을 탐지하기 시작하면 탐지 시점이
 * Event의 도착이 아니라 시각에 묶여, 장애를 알아채는 데 걸리는 시간이 Scheduler 주기만큼 늘어난다.
 * <p>
 * 집계의 근거는 MySQL의 Incident뿐이다. 하루 총 요청 수나 응답 시간 분포는 Redis의 Rolling Metric에만
 * 있고 그 Key의 수명은 300초라, 하루가 끝난 뒤에는 남아 있지 않다. 그것까지 담으려면 Metric을 따로
 * 영속화해야 하며 그것은 이 Report와 다른 크기의 일이다. 지금 정확히 셀 수 있는 것만 센다.
 * <p>
 * 같은 날짜로 몇 번을 불러도 Report는 하나이다. 먼저 있는지 확인하고, 없으면 만들되, 만드는 도중에
 * 다른 실행이 먼저 만들었으면 그것을 그대로 돌려준다. 확인만으로는 부족하다. 두 실행이 겹치면 둘 다
 * "없다"를 보고 둘 다 만들려 들기 때문에, 마지막으로 막는 것은 언제나 {@code report_date}의 UNIQUE이다.
 * <p>
 * 만드는 일만 Transaction 안에 둔다. 앞의 존재 확인과 뒤의 UNIQUE 위반 처리는 그 밖이어야 한다.
 * 제약 위반으로 실패한 Transaction은 되돌아가는 것이 확정된 상태라, 그 안에서 다시 조회해도 결과를
 * 쓸 수 없기 때문이다. {@code @Transactional}이 아니라 TransactionTemplate을 쓰는 이유가 그것이며,
 * {@code IncidentDetector}가 같은 방식으로 경계를 손으로 여닫는다.
 * <p>
 * 집계 조회들을 한 Transaction에 묶는 것은 일관성 때문이 아니다. 이 Report가 보는 구간은 이미 끝난
 * 하루이고 지금 일어나는 변경은 모두 오늘의 시각을 갖기 때문에, 조회 사이에 무엇이 바뀌어도 세는
 * 값은 달라지지 않는다. 다만 여섯 번의 조회와 한 번의 저장이 한 경계 안에서 끝나는 편이 연결을
 * 여닫는 횟수도 적고 무엇이 하나의 작업인지도 분명하다.
 */
@Service
public class DailyIncidentReportService {

	/**
	 * 목록으로 돌려주는 최근 Report 수이다.
	 * <p>
	 * 전부 돌려주지 않는다. Report는 하루에 하나씩 무한히 쌓이므로 제한이 없으면 응답이 시간이 갈수록
	 * 커진다. 한 달치면 최근 추세를 보기에 충분하고, 그보다 오래된 것을 봐야 할 때에는 날짜로 하나씩
	 * 조회한다. 지금 필요하지 않은 Pagination을 이것 때문에 만들지는 않는다.
	 */
	static final int RECENT_REPORT_LIMIT = 30;

	private final DailyIncidentReportRepository dailyIncidentReportRepository;

	private final IncidentRepository incidentRepository;

	private final TransactionTemplate transactionTemplate;

	public DailyIncidentReportService(DailyIncidentReportRepository dailyIncidentReportRepository,
			IncidentRepository incidentRepository, TransactionTemplate transactionTemplate) {
		this.dailyIncidentReportRepository = dailyIncidentReportRepository;
		this.incidentRepository = incidentRepository;
		this.transactionTemplate = transactionTemplate;
	}

	/**
	 * 한 날짜의 Report를 만든다. 이미 있으면 만들지 않고 그것을 돌려준다.
	 * <p>
	 * 이 package 밖에서는 부를 수 없다. Report를 만드는 것은 Scheduler 하나이며 REST에는 생성 경로가
	 * 없다. 운영자가 아무 때나 batch를 돌릴 수 있게 열어 두면 인증 없는 API가 DB 집계를 실행하게 되고,
	 * Report가 언제 어떤 기준으로 만들어지는지도 더 이상 하나로 정해지지 않는다.
	 * <p>
	 * 이미 있는 Report를 다시 계산하지 않는다. 이 기록은 만들어진 시점의 snapshot이고, 다시 계산하면
	 * 어제 본 숫자와 오늘 보는 숫자가 달라진다. 늦게 도착한 Event 때문에 값이 조금 달라질 수 있다는
	 * 것은 사실이지만, 그것을 쫓아 계속 고치는 것은 Report가 아니라 계속 변하는 조회가 된다.
	 * <p>
	 * Incident가 하나도 없는 날짜에도 Report를 만든다. 아무 일도 없었다는 것 역시 그 하루의 결과이며,
	 * Report가 없는 날짜와 Scheduler가 돌지 못한 날짜는 구분되어야 한다.
	 * <p>
	 * {@code generatedAt}을 호출자가 넘긴다. Scheduler가 실행마다 시각을 한 번만 읽어 집계 기준과
	 * 기록 시각이 같은 순간을 가리키게 한다.
	 */
	DailyIncidentReportGenerationResult generate(LocalDate reportDate, Instant generatedAt) {
		Optional<DailyIncidentReport> existing = this.dailyIncidentReportRepository.findByReportDate(reportDate);
		if (existing.isPresent()) {
			return new DailyIncidentReportGenerationResult(existing.get(), false);
		}
		try {
			DailyIncidentReport created = create(new DailyIncidentReportPeriod(reportDate), generatedAt);
			return new DailyIncidentReportGenerationResult(created, true);
		}
		catch (DataIntegrityViolationException ex) {
			// 같은 날짜를 다른 실행이 먼저 저장했다면 그것이 정답이다. 다른 제약이 깨진 것이라면 그
			// 날짜의 Report는 여전히 없으므로 원래 실패를 그대로 올려 보낸다.
			return this.dailyIncidentReportRepository.findByReportDate(reportDate)
				.map((report) -> new DailyIncidentReportGenerationResult(report, false))
				.orElseThrow(() -> ex);
		}
	}

	/**
	 * 최근 Report를 새것부터 돌려준다.
	 * <p>
	 * 순서를 화면에 맡기지 않는다. 최근 것이 먼저라는 것이 이 목록의 뜻이고, 그것이 정해져 있어야
	 * 개수 제한도 "최근 몇 개"라는 뜻을 갖는다.
	 */
	public List<DailyIncidentReportResponse> recentReports() {
		return this.dailyIncidentReportRepository.findAllByOrderByReportDateDesc(Limit.of(RECENT_REPORT_LIMIT))
			.stream()
			.map(DailyIncidentReportResponse::from)
			.toList();
	}

	/**
	 * 날짜 하나의 Report를 돌려준다. 아직 만들어지지 않은 날짜와 아주 오래된 날짜는 똑같이 없는
	 * 것이며, 그 둘을 나눌 근거가 이 Class에는 없다.
	 */
	public DailyIncidentReportResponse report(LocalDate reportDate) {
		return this.dailyIncidentReportRepository.findByReportDate(reportDate)
			.map(DailyIncidentReportResponse::from)
			.orElseThrow(() -> new DailyIncidentReportNotFoundException(reportDate));
	}

	private DailyIncidentReport create(DailyIncidentReportPeriod period, Instant generatedAt) {
		return this.transactionTemplate.execute((status) -> this.dailyIncidentReportRepository
			.saveAndFlush(DailyIncidentReport.of(period, aggregate(period), generatedAt)));
	}

	/**
	 * 구간 안의 Incident를 세어 집계 결과를 만든다.
	 * <p>
	 * 열림, 확인, 해결을 각각 그 일이 일어난 시각으로 센다. 하나의 Incident가 여러 날에 걸쳐 있으면
	 * 각 날짜의 Report에 그 날 일어난 일로 등장한다. 열린 날짜에 세 가지를 모두 몰아 세면 아직 끝나지
	 * 않은 Incident의 해결을 셀 수 없고, Report는 그 날 무슨 일이 있었는지가 아니라 그 날 열린 것이
	 * 나중에 어떻게 됐는지를 말하게 된다. 뒤엣것은 이미 만들어진 Report를 계속 고쳐야만 유지된다.
	 * <p>
	 * 열린 건수를 종류별 건수의 합으로 구하지 않고 따로 센다. 합으로 구하면 종류가 하나 늘어나는 날
	 * 총계가 조용히 모자라진다.
	 */
	private DailyIncidentAggregate aggregate(DailyIncidentReportPeriod period) {
		Instant start = period.start();
		Instant end = period.end();
		List<IncidentResolution> resolutions = this.incidentRepository.findResolutionsIn(start, end);
		LongSummaryStatistics resolutionSeconds = resolutions.stream()
			.mapToLong(IncidentResolution::durationSeconds)
			.summaryStatistics();
		long resolvedCount = resolutionSeconds.getCount();
		return new DailyIncidentAggregate(this.incidentRepository.countOpenedIn(start, end),
				this.incidentRepository.countOpenedInByType(start, end, IncidentType.ERROR_RATE),
				this.incidentRepository.countOpenedInByType(start, end, IncidentType.LATENCY),
				this.incidentRepository.countAcknowledgedIn(start, end), resolvedCount,
				this.incidentRepository.countUnresolvedAt(end), averageSeconds(resolutionSeconds),
				(resolvedCount > 0) ? resolutionSeconds.getMax() : null);
	}

	/**
	 * 해결된 Incident가 없으면 평균도 없다. 0을 넣으면 아무것도 해결하지 못한 하루와 모든 것을 즉시
	 * 해결한 하루가 같은 값을 갖는다.
	 * <p>
	 * 초 단위로 반올림한다. {@code getAverage()}가 돌려주는 실수를 그대로 두면 저장할 때 잘려 언제나
	 * 내림이 되고, 그 규칙이 코드에 남지 않는다.
	 */
	private static Long averageSeconds(LongSummaryStatistics resolutionSeconds) {
		if (resolutionSeconds.getCount() == 0) {
			return null;
		}
		return BigDecimal.valueOf(resolutionSeconds.getSum())
			.divide(BigDecimal.valueOf(resolutionSeconds.getCount()), 0, RoundingMode.HALF_UP)
			.longValueExact();
	}

}

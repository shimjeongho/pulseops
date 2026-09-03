package com.pulseops.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseops.MySqlTestcontainersConfiguration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 MySQL에 저장된 Daily Incident Report가 Client가 읽는 형태로 나오는지 끝에서 끝까지 확인한다.
 * <p>
 * 생성 Endpoint가 없다는 것도 이 계약의 일부이다. Report를 만드는 것은 Scheduler 하나이며, 그것을
 * API로 열면 인증 없는 요청이 아무 때나 집계를 실행할 수 있게 된다.
 * <p>
 * 오류 응답의 code와 본문 모양은 {@code GlobalExceptionHandlerIntegrationTest}가 같은 Endpoint로
 * 고정한다. 여기서는 어떤 요청이 무엇으로 거절되는지만 본다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class DailyIncidentReportApiIntegrationTest {

	private static final List<String> REPORT_FIELDS = List.of("reportDate", "periodStart", "periodEnd", "openedCount",
			"errorRateOpenedCount", "latencyOpenedCount", "acknowledgedCount", "resolvedCount",
			"unresolvedAtPeriodEndCount", "averageResolutionSeconds", "maxResolutionSeconds", "generatedAt");

	private static final LocalDate REPORT_DATE = LocalDate.of(2026, 9, 3);

	private static final Instant GENERATED_AT = Instant.parse("2026-09-04T00:05:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private DailyIncidentReportRepository dailyIncidentReportRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@AfterEach
	void removeReports() {
		this.dailyIncidentReportRepository.deleteAll();
	}

	/** Report가 하나도 없는 것은 정상이며 빈 배열이다. 없는 것과 실패는 같은 모양일 수 없다. */
	@Test
	void returnsAnEmptyArrayWhenNoReportHasBeenGenerated() throws Exception {
		JsonNode body = getRecent();

		assertThat(body.isArray()).isTrue();
		assertThat(body).isEmpty();
	}

	@Test
	void returnsEveryFieldOfAReport() throws Exception {
		save(REPORT_DATE, new DailyIncidentAggregate(5, 3, 2, 4, 6, 7, 120L, 300L));

		JsonNode item = getRecent().get(0);

		assertThat(item.propertyNames()).containsExactlyInAnyOrderElementsOf(REPORT_FIELDS);
		assertThat(item.get("reportDate").stringValue()).isEqualTo("2026-09-03");
		assertThat(item.get("periodStart").stringValue()).isEqualTo("2026-09-03T00:00:00Z");
		assertThat(item.get("periodEnd").stringValue()).isEqualTo("2026-09-04T00:00:00Z");
		assertThat(item.get("openedCount").longValue()).isEqualTo(5);
		assertThat(item.get("errorRateOpenedCount").longValue()).isEqualTo(3);
		assertThat(item.get("latencyOpenedCount").longValue()).isEqualTo(2);
		assertThat(item.get("acknowledgedCount").longValue()).isEqualTo(4);
		assertThat(item.get("resolvedCount").longValue()).isEqualTo(6);
		assertThat(item.get("unresolvedAtPeriodEndCount").longValue()).isEqualTo(7);
		assertThat(item.get("averageResolutionSeconds").longValue()).isEqualTo(120);
		assertThat(item.get("maxResolutionSeconds").longValue()).isEqualTo(300);
		assertThat(item.get("generatedAt").stringValue()).isEqualTo("2026-09-04T00:05:00Z");
	}

	/** 해결 시간 통계는 없으면 null이며 0으로 바뀌지 않는다. */
	@Test
	void keepsTheResolutionStatisticsNullWhenNothingWasResolved() throws Exception {
		save(REPORT_DATE, new DailyIncidentAggregate(1, 1, 0, 0, 0, 1, null, null));

		JsonNode item = getRecent().get(0);

		assertThat(item.get("averageResolutionSeconds").isNull()).isTrue();
		assertThat(item.get("maxResolutionSeconds").isNull()).isTrue();
	}

	/** 최근 것이 먼저 온다. 저장한 순서가 아니라 날짜가 순서를 정한다. */
	@Test
	void returnsTheMostRecentReportFirst() throws Exception {
		save(LocalDate.of(2026, 9, 1), empty());
		save(LocalDate.of(2026, 9, 3), empty());
		save(LocalDate.of(2026, 9, 2), empty());

		JsonNode body = getRecent();

		assertThat(body.get(0).get("reportDate").stringValue()).isEqualTo("2026-09-03");
		assertThat(body.get(1).get("reportDate").stringValue()).isEqualTo("2026-09-02");
		assertThat(body.get(2).get("reportDate").stringValue()).isEqualTo("2026-09-01");
	}

	/**
	 * 목록은 최근 몇 개까지만 돌려준다. Report는 하루에 하나씩 무한히 쌓이므로 제한이 없으면 응답이
	 * 시간이 갈수록 커진다.
	 */
	@Test
	void returnsOnlyTheMostRecentReports() throws Exception {
		LocalDate oldest = REPORT_DATE.minusDays(DailyIncidentReportService.RECENT_REPORT_LIMIT);
		for (int day = 0; day <= DailyIncidentReportService.RECENT_REPORT_LIMIT; day++) {
			save(oldest.plusDays(day), empty());
		}

		JsonNode body = getRecent();

		assertThat(body).hasSize(DailyIncidentReportService.RECENT_REPORT_LIMIT);
		assertThat(body.get(0).get("reportDate").stringValue()).isEqualTo(REPORT_DATE.toString());
		assertThat(body.get(DailyIncidentReportService.RECENT_REPORT_LIMIT - 1).get("reportDate").stringValue())
			.isEqualTo(oldest.plusDays(1).toString());
	}

	@Test
	void returnsOneReportByItsDate() throws Exception {
		save(REPORT_DATE, new DailyIncidentAggregate(5, 3, 2, 4, 6, 7, 120L, 300L));

		JsonNode body = readJson(this.mockMvc.perform(get("/api/reports/daily/{reportDate}", "2026-09-03"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)));

		assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(REPORT_FIELDS);
		assertThat(body.get("reportDate").stringValue()).isEqualTo("2026-09-03");
		assertThat(body.get("openedCount").longValue()).isEqualTo(5);
	}

	/** 아직 만들어지지 않은 날짜는 없는 것이다. */
	@Test
	void refusesADateWithoutAReport() throws Exception {
		this.mockMvc.perform(get("/api/reports/daily/{reportDate}", "2026-09-03")).andExpect(status().isNotFound());
	}

	/** 날짜로 읽을 수 없는 경로는 요청 자체가 성립하지 않는다. */
	@Test
	void refusesAPathThatIsNotADate() throws Exception {
		this.mockMvc.perform(get("/api/reports/daily/{reportDate}", "yesterday"))
			.andExpect(status().isBadRequest());
		this.mockMvc.perform(get("/api/reports/daily/{reportDate}", "2026-13-40"))
			.andExpect(status().isBadRequest());
	}

	/** Report를 만드는 경로는 API에 없다. */
	@Test
	void doesNotExposeAGenerationEndpoint() throws Exception {
		this.mockMvc.perform(post("/api/reports/daily")).andExpect(status().isMethodNotAllowed());
	}

	private JsonNode getRecent() throws Exception {
		return readJson(this.mockMvc.perform(get("/api/reports/daily"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)));
	}

	private JsonNode readJson(ResultActions actions) throws Exception {
		return this.objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	private void save(LocalDate reportDate, DailyIncidentAggregate aggregate) {
		this.dailyIncidentReportRepository
			.saveAndFlush(DailyIncidentReport.of(new DailyIncidentReportPeriod(reportDate), aggregate, GENERATED_AT));
	}

	private static DailyIncidentAggregate empty() {
		return new DailyIncidentAggregate(0, 0, 0, 0, 0, 0, null, null);
	}

}

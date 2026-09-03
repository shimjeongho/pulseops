package com.pulseops.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.incident.Incident;
import com.pulseops.incident.IncidentRepository;
import com.pulseops.incident.IncidentSeverity;
import com.pulseops.incident.IncidentType;
import com.pulseops.metric.MetricQueryService;
import com.pulseops.metric.MetricSnapshot;
import com.pulseops.metric.MetricStoreUnavailableException;
import com.pulseops.report.DailyIncidentAggregate;
import com.pulseops.report.DailyIncidentReport;
import com.pulseops.report.DailyIncidentReportPeriod;
import com.pulseops.report.DailyIncidentReportRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실패했을 때 Client가 무엇을 받는지 고정한다.
 * <p>
 * 실제 Endpoint로 확인한다. 검증 대상이 "이 예외를 이 상태로 옮긴다"는 규칙이 아니라 "이 요청이
 * 실패하면 이런 응답이 나간다"는 계약이기 때문이다.
 * <p>
 * Redis Container를 띄우지 않는다. 저장소를 실제로 멈춰 세우는 대신 조회 계층이 실패를 알리는
 * 지점부터 확인하며, Redis가 정말 그 예외를 올려 보내는지는 Metric 쪽 Integration Test가 맡는다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class GlobalExceptionHandlerIntegrationTest {

	private static final List<String> ERROR_FIELDS = List.of("timestamp", "status", "code", "message", "path");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private DailyIncidentReportRepository dailyIncidentReportRepository;

	@AfterEach
	void removeIncidents() {
		this.incidentRepository.deleteAll();
		this.dailyIncidentReportRepository.deleteAll();
	}

	@MockitoBean
	private MetricQueryService metricQueryService;

	/**
	 * Metric은 Redis에만 있어 읽지 못하면 돌려줄 값이 없다. 200에 빈 목록을 담으면 장애가 "관측 중인
	 * API 없음"으로 보인다.
	 */
	@Test
	void answersWithServiceUnavailableWhenTheMetricStoreCannotBeRead() throws Exception {
		given(this.metricQueryService.currentMetrics())
			.willThrow(new MetricStoreUnavailableException(new RedisConnectionFailureException("down")));

		JsonNode body = errorBody(get("/api/metrics"), 503);

		assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(ERROR_FIELDS);
		assertThat(body.get("code").stringValue()).isEqualTo("METRIC_STORE_UNAVAILABLE");
		assertThat(body.get("status").intValue()).isEqualTo(503);
		assertThat(body.get("path").stringValue()).isEqualTo("/api/metrics");
		assertThat(body.get("timestamp").stringValue()).endsWith("Z");
	}

	@Test
	void answersWithInternalErrorForAnUnexpectedFailure() throws Exception {
		given(this.metricQueryService.currentMetrics()).willThrow(new IllegalStateException("boom"));

		JsonNode body = errorBody(get("/api/metrics"), 500);

		assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(ERROR_FIELDS);
		assertThat(body.get("code").stringValue()).isEqualTo("INTERNAL_ERROR");
	}

	/**
	 * 예외의 message에는 Key나 값, 내부 경로가 담겨 올 수 있다. 응답에는 고정된 문장만 나가야 하며
	 * Java Type 이름도 code가 되지 않는다.
	 */
	@Test
	void neverLeaksTheExceptionDetailToTheClient() throws Exception {
		given(this.metricQueryService.currentMetrics())
			.willThrow(new IllegalStateException("pulseops:metric:series 를 읽지 못했다"));

		JsonNode body = errorBody(get("/api/metrics"), 500);

		assertThat(body.get("message").stringValue()).doesNotContain("pulseops:").doesNotContain("IllegalState");
		assertThat(body.get("code").stringValue()).doesNotContain("Exception");
		assertThat(body.toString()).doesNotContain("trace").doesNotContain("java.lang");
	}

	/**
	 * Spring MVC가 스스로 상태를 정하는 실패는 catch-all이 가로채면 안 된다. 없는 경로가 500이 되면
	 * Client가 자기 요청의 잘못을 서버 장애로 읽고, 운영자는 존재하지 않는 장애를 쫓게 된다.
	 */
	@Test
	void leavesFrameworkDecidedStatusesAlone() throws Exception {
		this.mockMvc.perform(get("/api/does-not-exist")).andExpect(status().isNotFound());
	}

	/**
	 * 없는 경로와 달리 이쪽은 Controller까지 요청이 닿기 전에 HandlerMapping이 거절하는 자리다. 두
	 * 경로가 서로 다르므로 경계를 각각 확인한다.
	 */
	@Test
	void leavesAnUnsupportedMethodAsMethodNotAllowed() throws Exception {
		this.mockMvc.perform(post("/api/metrics")).andExpect(status().isMethodNotAllowed());
	}

	/**
	 * 없는 Incident는 서버의 실패가 아니라 요청이 가리킨 것이 없다는 사실이므로 404이며, code로
	 * 무엇이 없었는지까지 알린다.
	 */
	@Test
	void answersWithNotFoundForAnIncidentThatDoesNotExist() throws Exception {
		JsonNode body = errorBody(get("/api/incidents/999999"), 404);

		assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(ERROR_FIELDS);
		assertThat(body.get("code").stringValue()).isEqualTo("INCIDENT_NOT_FOUND");
		assertThat(body.get("path").stringValue()).isEqualTo("/api/incidents/999999");
	}

	/**
	 * 숫자가 아닌 ID는 Controller에 닿기 전에 변환이 실패하지만, 그 실패는 Controller를 향한 요청에서
	 * 일어나므로 이 Class의 범위 안이다. catch-all에 맡기면 Client의 잘못된 요청이 서버 장애로 나간다.
	 */
	@Test
	void answersWithBadRequestWhenThePathVariableIsNotANumber() throws Exception {
		JsonNode body = errorBody(get("/api/incidents/abc"), 400);

		assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(ERROR_FIELDS);
		assertThat(body.get("code").stringValue()).isEqualTo("INVALID_REQUEST");
	}

	/**
	 * 아직 만들어지지 않은 날짜의 Daily Report는 없는 것이며, Incident를 찾지 못한 것과 code로
	 * 구분된다. 같은 404라도 무엇이 없었는지를 Client가 알아야 다음에 무엇을 할지 정할 수 있다.
	 */
	@Test
	void answersWithNotFoundForADailyReportThatDoesNotExist() throws Exception {
		JsonNode body = errorBody(get("/api/reports/daily/2026-09-03"), 404);

		assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(ERROR_FIELDS);
		assertThat(body.get("code").stringValue()).isEqualTo("DAILY_REPORT_NOT_FOUND");
		assertThat(body.get("path").stringValue()).isEqualTo("/api/reports/daily/2026-09-03");
	}

	/** 날짜로 읽을 수 없는 경로는 Client의 잘못이며 서버 장애가 아니다. */
	@Test
	void answersWithBadRequestWhenThePathVariableIsNotADate() throws Exception {
		JsonNode body = errorBody(get("/api/reports/daily/2026-13-40"), 400);

		assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(ERROR_FIELDS);
		assertThat(body.get("code").stringValue()).isEqualTo("INVALID_REQUEST");
	}

	/** 있는 날짜는 정상적으로 나온다. 위의 404가 언제나 404인 것이 아님을 함께 고정한다. */
	@Test
	void answersWithTheReportWhenTheDateHasOne() throws Exception {
		this.dailyIncidentReportRepository
			.saveAndFlush(DailyIncidentReport.of(new DailyIncidentReportPeriod(LocalDate.of(2026, 9, 3)),
					new DailyIncidentAggregate(0, 0, 0, 0, 0, 0, null, null),
					Instant.parse("2026-09-04T00:05:00Z")));

		this.mockMvc.perform(get("/api/reports/daily/2026-09-03")).andExpect(status().isOk());
	}

	/**
	 * 사람이 확인 버튼을 누르는 사이에 Detector가 같은 Incident를 끝내는 것은 정상적으로 일어나는
	 * 경쟁이다. 그때 요청은 틀리지 않았고 서버도 실패하지 않았으므로 400도 500도 아닌 409이다.
	 */
	@Test
	void answersWithConflictWhenTheIncidentCannotBeAcknowledged() throws Exception {
		Long id = saveResolvedIncident();

		JsonNode body = errorBody(post("/api/incidents/" + id + "/acknowledge"), 409);

		assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(ERROR_FIELDS);
		assertThat(body.get("code").stringValue()).isEqualTo("INCIDENT_ACK_NOT_ALLOWED");
		assertThat(body.get("status").intValue()).isEqualTo(409);
		assertThat(body.get("path").stringValue()).isEqualTo("/api/incidents/" + id + "/acknowledge");
	}

	/** 거절의 근거는 Incident가 던진 예외에만 남고 응답에는 상태 이름조차 나가지 않는다. */
	@Test
	void neverLeaksTheRefusedStatusToTheClient() throws Exception {
		Long id = saveResolvedIncident();

		JsonNode body = errorBody(post("/api/incidents/" + id + "/acknowledge"), 409);

		assertThat(body.get("message").stringValue()).doesNotContain("RESOLVED").doesNotContain("status=");
		assertThat(body.toString()).doesNotContain("IllegalState").doesNotContain("java.lang");
	}

	private Long saveResolvedIncident() {
		MetricSnapshot trigger = new MetricSnapshot("demo-shop", "POST", "/api/orders",
				Instant.parse("2026-09-01T05:59:00Z"), Instant.parse("2026-09-01T06:00:00Z"), 120, 30, 25.0, 240L);
		Incident incident = Incident.open(IncidentType.ERROR_RATE, IncidentSeverity.WARNING, trigger,
				Instant.parse("2026-09-01T06:00:00Z"));
		incident.resolve(Instant.parse("2026-09-01T06:05:00Z"));
		return this.incidentRepository.saveAndFlush(incident).getId();
	}

	private JsonNode errorBody(org.springframework.test.web.servlet.RequestBuilder request, int expectedStatus)
			throws Exception {
		String body = this.mockMvc.perform(request)
			.andExpect(status().is(expectedStatus))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return this.objectMapper.readTree(body);
	}

}

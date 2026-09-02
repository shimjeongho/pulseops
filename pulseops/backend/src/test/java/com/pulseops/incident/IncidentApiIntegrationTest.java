package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.metric.MetricSnapshot;
import java.time.Instant;
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
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 MySQL에 저장된 Incident가 Client가 읽는 형태로 나오는지 끝에서 끝까지 확인한다.
 * <p>
 * 저장소를 흉내 내지 않는다. 상태로 거르는 일과 값을 담는 일이 저장과 조회를 거쳐 실제로 이어져
 * 있는지가 이 단계에서 확인할 것이다.
 * <p>
 * 오류 응답의 code와 본문 모양은 여기서 확인하지 않는다. 무엇이 실패하면 어떤 code가 나가는지는
 * {@code GlobalExceptionHandlerIntegrationTest}가 같은 Endpoint로 이미 고정하고 있고, 같은 계약을 두
 * 곳에 적으면 한쪽만 바뀌었을 때 어느 쪽이 계약인지 말할 수 없다. 여기서 보는 것은 어떤 상태 전이가
 * 거절되는지와 거절이 저장된 값을 건드리지 않는지이며, 그것은 오류 본문이 아니라 Incident의 계약이다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class IncidentApiIntegrationTest {

	private static final List<String> SUMMARY_FIELDS = List.of("id", "service", "method", "endpoint", "type",
			"severity", "status", "openedAt", "acknowledgedAt");

	private static final List<String> DETAIL_FIELDS = List.of("id", "service", "method", "endpoint", "type", "severity",
			"status", "openedAt", "acknowledgedAt", "resolvedAt", "triggerWindowStart", "triggerWindowEnd",
			"triggerRequestCount", "triggerErrorCount", "triggerErrorRate", "triggerP95LatencyMs");

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "POST";

	private static final String ENDPOINT = "/api/orders";

	private static final Instant WINDOW_START = Instant.parse("2026-09-01T05:59:00Z");

	private static final Instant OPENED_AT = Instant.parse("2026-09-01T06:00:00Z");

	private static final Instant EARLIER = Instant.parse("2026-09-01T05:50:00Z");

	private static final Instant ACKNOWLEDGED_AT = Instant.parse("2026-09-01T06:01:00Z");

	private static final Instant RESOLVED_AT = Instant.parse("2026-09-01T06:05:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private IncidentRepository incidentRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@AfterEach
	void removeIncidents() {
		this.incidentRepository.deleteAll();
	}

	/** 처리 중인 Incident가 없는 것은 정상이며 빈 배열이다. 없는 것과 실패는 같은 모양일 수 없다. */
	@Test
	void returnsAnEmptyArrayWhenNothingIsActive() throws Exception {
		JsonNode body = getActive();

		assertThat(body.isArray()).isTrue();
		assertThat(body).isEmpty();
	}

	@Test
	void returnsEveryActiveIncidentWithItsSummaryFields() throws Exception {
		Incident incident = save(open(ENDPOINT, IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL, OPENED_AT));

		JsonNode item = getActive().get(0);

		assertThat(item.propertyNames()).containsExactlyInAnyOrderElementsOf(SUMMARY_FIELDS);
		assertThat(item.get("id").longValue()).isEqualTo(incident.getId());
		assertThat(item.get("service").stringValue()).isEqualTo(SERVICE);
		assertThat(item.get("method").stringValue()).isEqualTo(METHOD);
		assertThat(item.get("endpoint").stringValue()).isEqualTo(ENDPOINT);
		assertThat(item.get("type").stringValue()).isEqualTo("ERROR_RATE");
		assertThat(item.get("severity").stringValue()).isEqualTo("CRITICAL");
		assertThat(item.get("status").stringValue()).isEqualTo("OPEN");
		assertThat(item.get("acknowledgedAt").isNull()).isTrue();
	}

	/** 확인된 Incident는 여전히 처리 중이며 언제부터 사람이 붙었는지도 목록에서 보여야 한다. */
	@Test
	void keepsAnAcknowledgedIncidentInTheActiveList() throws Exception {
		Incident incident = open(ENDPOINT, IncidentType.ERROR_RATE, IncidentSeverity.WARNING, OPENED_AT);
		incident.acknowledge(ACKNOWLEDGED_AT);
		save(incident);

		JsonNode item = getActive().get(0);

		assertThat(item.get("status").stringValue()).isEqualTo("ACKNOWLEDGED");
		assertThat(item.get("acknowledgedAt").stringValue()).isEqualTo("2026-09-01T06:01:00Z");
	}

	/**
	 * 끝난 Incident는 목록에서 빠지지만 기록은 남는다. 목록에서 사라졌다는 이유로 상세까지 닫히면
	 * 무슨 일이 있었는지 돌아볼 방법이 없어진다.
	 */
	@Test
	void dropsAResolvedIncidentFromTheListButKeepsItsDetail() throws Exception {
		Incident incident = open(ENDPOINT, IncidentType.ERROR_RATE, IncidentSeverity.WARNING, OPENED_AT);
		incident.resolve(RESOLVED_AT);
		Long id = save(incident).getId();

		assertThat(getActive()).isEmpty();

		JsonNode detail = getDetail(id);

		assertThat(detail.get("status").stringValue()).isEqualTo("RESOLVED");
		assertThat(detail.get("resolvedAt").stringValue()).isEqualTo("2026-09-01T06:05:00Z");
	}

	/**
	 * 심한 것이 먼저, 같은 심각도면 최근에 열린 것이 먼저다. 심각도가 DB에 문자열로 저장되므로 정렬을
	 * 저장소에 맡기면 이름의 사전순이 심각도의 순서인 것처럼 동작한다. WARNING을 가장 최근으로 두어,
	 * 열린 시각만 보거나 심각도의 순서를 뒤집는 구현이 이 자리에서 드러나게 한다.
	 */
	@Test
	void ordersBySeverityThenByHowRecentlyItOpened() throws Exception {
		save(open("/api/products", IncidentType.ERROR_RATE, IncidentSeverity.WARNING, OPENED_AT));
		save(open("/api/orders", IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL, EARLIER));
		save(open("/api/payments", IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL, OPENED_AT));

		JsonNode body = getActive();

		assertThat(body).hasSize(3);
		assertThat(body.get(0).get("endpoint").stringValue()).isEqualTo("/api/payments");
		assertThat(body.get(1).get("endpoint").stringValue()).isEqualTo("/api/orders");
		assertThat(body.get(2).get("endpoint").stringValue()).isEqualTo("/api/products");
	}

	@Test
	void returnsTheDetailWithTheObservationThatOpenedTheIncident() throws Exception {
		Long id = save(open(ENDPOINT, IncidentType.ERROR_RATE, IncidentSeverity.WARNING, OPENED_AT)).getId();

		JsonNode detail = getDetail(id);

		assertThat(detail.propertyNames()).containsExactlyInAnyOrderElementsOf(DETAIL_FIELDS);
		assertThat(detail.get("id").longValue()).isEqualTo(id);
		assertThat(detail.get("triggerWindowStart").stringValue()).isEqualTo("2026-09-01T05:59:00Z");
		assertThat(detail.get("triggerWindowEnd").stringValue()).isEqualTo("2026-09-01T06:00:00Z");
		assertThat(detail.get("triggerRequestCount").longValue()).isEqualTo(120L);
		assertThat(detail.get("triggerErrorCount").longValue()).isEqualTo(30L);
		assertThat(detail.get("triggerP95LatencyMs").longValue()).isEqualTo(240L);
		assertThat(detail.get("acknowledgedAt").isNull()).isTrue();
		assertThat(detail.get("resolvedAt").isNull()).isTrue();
	}

	/** 자리수는 판정의 근거로 저장된 값 그대로여야 하며 문자열이 아닌 숫자로 나간다. */
	@Test
	void reportsTheTriggerErrorRateAsANumberWithTwoDecimalPlaces() throws Exception {
		Long id = save(open(ENDPOINT, IncidentType.ERROR_RATE, IncidentSeverity.WARNING, OPENED_AT)).getId();

		JsonNode detail = getDetail(id);

		assertThat(detail.get("triggerErrorRate").isNumber()).isTrue();
		assertThat(detail.get("triggerErrorRate").decimalValue()).isEqualByComparingTo("25.00");
	}

	/** 응답 시간을 재지 못한 창이 가장 빠른 창이 되면 안 되므로 0으로 바꾸지 않는다. */
	@Test
	void keepsAMissingTriggerP95AsNull() throws Exception {
		MetricSnapshot trigger = new MetricSnapshot(SERVICE, METHOD, ENDPOINT, WINDOW_START, OPENED_AT, 120, 30, 25.0,
				null);
		Long id = save(Incident.open(IncidentType.LATENCY, IncidentSeverity.WARNING, trigger, OPENED_AT)).getId();

		assertThat(getDetail(id).get("triggerP95LatencyMs").isNull()).isTrue();
	}

	/**
	 * active_key는 활성 Incident의 유일성을 지키려고 DB가 파생시키는 열이고 updatedAt은 심각도가 언제
	 * 올랐는지만 뜻한다. 둘 다 밖에서는 쓸 곳이 없는 내부 사정이며, 한 번 나가면 Client가 그것에 묶인다.
	 */
	@Test
	void neverExposesTheInternalColumnsOfTheEntity() throws Exception {
		Long id = save(open(ENDPOINT, IncidentType.ERROR_RATE, IncidentSeverity.WARNING, OPENED_AT)).getId();

		String active = body(get("/api/incidents/active"));
		String detail = body(get("/api/incidents/" + id));

		assertThat(active).doesNotContain("activeKey").doesNotContain("active_key").doesNotContain("updatedAt");
		assertThat(detail).doesNotContain("activeKey").doesNotContain("active_key").doesNotContain("updatedAt");
		assertThat(detail).doesNotContain("hibernate").doesNotContain("detectionState");
	}

	/** 시각은 UTC 기준 ISO-8601 문자열로 나가야 Client가 자기 시간대로 바꿔 보여 줄 수 있다. */
	@Test
	void writesEveryTimestampAsAnIsoUtcString() throws Exception {
		save(open(ENDPOINT, IncidentType.ERROR_RATE, IncidentSeverity.WARNING, OPENED_AT));

		assertThat(getActive().get(0).get("openedAt").stringValue()).isEqualTo("2026-09-01T06:00:00Z");
	}

	/** 확인 처리는 갱신된 상태를 그대로 돌려주어 Client가 다시 조회하지 않아도 되게 한다. */
	@Test
	void acknowledgesAnOpenIncidentAndReturnsItsNewState() throws Exception {
		Long id = save(open(ENDPOINT, IncidentType.ERROR_RATE, IncidentSeverity.WARNING, OPENED_AT)).getId();

		JsonNode body = acknowledgeOk(id);

		assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(DETAIL_FIELDS);
		assertThat(body.get("id").longValue()).isEqualTo(id);
		assertThat(body.get("status").stringValue()).isEqualTo("ACKNOWLEDGED");
		assertThat(body.get("acknowledgedAt").isNull()).isFalse();
		assertThat(body.get("acknowledgedAt").stringValue()).endsWith("Z");
	}

	/** 응답이 아니라 저장된 상태가 계약이다. 응답만 바뀌고 DB가 그대로면 새로고침에 확인이 사라진다. */
	@Test
	void storesTheAcknowledgement() throws Exception {
		Long id = save(open(ENDPOINT, IncidentType.ERROR_RATE, IncidentSeverity.WARNING, OPENED_AT)).getId();

		acknowledgeOk(id);

		assertThat(this.incidentRepository.findById(id)).get().satisfies((incident) -> {
			assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
			assertThat(incident.getAcknowledgedAt()).isNotNull();
		});
	}

	/** 확인된 Incident는 여전히 처리 중이므로 목록에서 사라지지 않는다. */
	@Test
	void keepsTheAcknowledgedIncidentInTheActiveList() throws Exception {
		Long id = save(open(ENDPOINT, IncidentType.ERROR_RATE, IncidentSeverity.WARNING, OPENED_AT)).getId();

		acknowledgeOk(id);

		JsonNode item = getActive().get(0);

		assertThat(item.get("id").longValue()).isEqualTo(id);
		assertThat(item.get("status").stringValue()).isEqualTo("ACKNOWLEDGED");
	}

	/**
	 * 두 번째 확인은 거절되고 첫 확인의 시각은 그대로 남는다. 나중 요청이 앞 요청의 시각을 덮으면
	 * 누가 언제부터 보고 있었는지가 사라진다.
	 */
	@Test
	void refusesASecondAcknowledgeAndKeepsTheFirstOne() throws Exception {
		Long id = save(open(ENDPOINT, IncidentType.ERROR_RATE, IncidentSeverity.WARNING, OPENED_AT)).getId();
		acknowledgeOk(id);
		Instant firstAcknowledgedAt = this.incidentRepository.findById(id).orElseThrow().getAcknowledgedAt();

		this.mockMvc.perform(post("/api/incidents/" + id + "/acknowledge")).andExpect(status().isConflict());

		assertThat(this.incidentRepository.findById(id)).get().satisfies((incident) -> {
			assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
			assertThat(incident.getAcknowledgedAt()).isEqualTo(firstAcknowledgedAt);
		});
	}

	/** 끝난 Incident는 확인 대상이 아니며 거절이 그 기록을 건드려서도 안 된다. */
	@Test
	void refusesToAcknowledgeAResolvedIncidentAndLeavesItUntouched() throws Exception {
		Incident incident = open(ENDPOINT, IncidentType.ERROR_RATE, IncidentSeverity.WARNING, OPENED_AT);
		incident.resolve(RESOLVED_AT);
		Long id = save(incident).getId();

		this.mockMvc.perform(post("/api/incidents/" + id + "/acknowledge")).andExpect(status().isConflict());

		assertThat(this.incidentRepository.findById(id)).get().satisfies((resolved) -> {
			assertThat(resolved.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
			assertThat(resolved.getResolvedAt()).isEqualTo(RESOLVED_AT);
			assertThat(resolved.getAcknowledgedAt()).isNull();
		});
	}

	/**
	 * 같은 계열에 오류율과 응답 시간의 Incident가 함께 열려 있을 수 있다. 확인은 ID로 지목한 하나에만
	 * 붙어야 하며, 계열로 번지면 사람이 보지 않은 이상까지 확인된 것으로 표시된다.
	 */
	@Test
	void acknowledgesOnlyTheIncidentThatWasNamed() throws Exception {
		Long errorRateId = save(open(ENDPOINT, IncidentType.ERROR_RATE, IncidentSeverity.WARNING, OPENED_AT)).getId();
		Long latencyId = save(open(ENDPOINT, IncidentType.LATENCY, IncidentSeverity.WARNING, OPENED_AT)).getId();

		acknowledgeOk(errorRateId);

		assertThat(this.incidentRepository.findById(latencyId)).get().satisfies((untouched) -> {
			assertThat(untouched.getStatus()).isEqualTo(IncidentStatus.OPEN);
			assertThat(untouched.getAcknowledgedAt()).isNull();
		});
	}

	private JsonNode acknowledgeOk(Long incidentId) throws Exception {
		return this.objectMapper.readTree(body(post("/api/incidents/" + incidentId + "/acknowledge")));
	}

	private JsonNode getActive() throws Exception {
		return this.objectMapper.readTree(body(get("/api/incidents/active")));
	}

	private JsonNode getDetail(Long incidentId) throws Exception {
		return this.objectMapper.readTree(body(get("/api/incidents/" + incidentId)));
	}

	private String body(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
		return this.mockMvc.perform(request)
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private Incident save(Incident incident) {
		return this.incidentRepository.saveAndFlush(incident);
	}

	private static Incident open(String endpoint, IncidentType type, IncidentSeverity severity, Instant openedAt) {
		MetricSnapshot trigger = new MetricSnapshot(SERVICE, METHOD, endpoint, WINDOW_START, openedAt, 120, 30, 25.0,
				240L);
		return Incident.open(type, severity, trigger, openedAt);
	}

}

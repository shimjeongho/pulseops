package com.pulseops.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseops.MySqlTestcontainersConfiguration;
import com.pulseops.metric.MetricQueryService;
import com.pulseops.metric.MetricStoreUnavailableException;
import java.util.List;
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

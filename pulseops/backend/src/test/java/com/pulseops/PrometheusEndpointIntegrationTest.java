package com.pulseops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseops.kafka.FakeKafkaConsumerLagClientConfiguration;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prometheus가 긁어 갈 자리에 무엇이 있고 무엇이 없는지를 고정한다.
 * <p>
 * 이 Application에는 인증 계층이 없다. Actuator에서 여는 것은 곧 공개하는 것이므로, 무엇이 열려 있는지를
 * 설정 파일의 한 줄로만 두지 않고 여기서 확인한다. 나중에 누군가 편의를 위해 exposure를 넓히면 이
 * 테스트가 먼저 깨진다.
 * <p>
 * Kafka Broker를 띄우지 않는다. Consumer Lag Gauge가 값을 갖는 데 필요한 것은 offset을 알려 주는 무언가
 * 뿐이고, 그 계산이 맞는지는 {@code ConsumerLagMetricsTest}와 Phase 11의 통합 테스트가 이미 본다.
 * <p>
 * MySQL은 Context에 Datasource가 있어 함께 띄운다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import({ MySqlTestcontainersConfiguration.class, FakeKafkaConsumerLagClientConfiguration.class })
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class PrometheusEndpointIntegrationTest {

	private static final String PROMETHEUS_PATH = "/actuator/prometheus";

	/**
	 * 하나라도 열리면 설정과 credential이 인증 없이 나간다. env와 configprops는 Claude API key와 GitHub
	 * token이 담긴 property를 그대로 보여 주고, beans와 mappings는 Application의 내부 구조를 알려 준다.
	 */
	private static final List<String> ENDPOINTS_THAT_MUST_STAY_CLOSED = List.of("/actuator/env",
			"/actuator/configprops", "/actuator/beans", "/actuator/mappings", "/actuator/metrics",
			"/actuator/loggers", "/actuator/threaddump", "/actuator/heapdump");

	/**
	 * 이 값들이 label 이름으로 들어가면 시계열 수가 사용자 트래픽만큼 늘어난다. 한 번 만들어진 시계열은
	 * 그 요청이 끝난 뒤에도 남으므로, 늘어난 수는 되돌아오지 않는다.
	 */
	private static final List<String> FORBIDDEN_LABEL_NAMES = List.of("requestId", "request_id", "eventId",
			"event_id", "serviceInstance", "service_instance", "errorMessage", "error_message", "incidentId",
			"incident_id");

	/** 설정 파일에 있는 credential이 metric 응답으로 새어 나가지 않는지 본다. */
	private static final List<String> SECRETS_THAT_MUST_NOT_APPEAR = List.of("ANTHROPIC_API_KEY", "GITHUB_TOKEN",
			"pulseops.ai.claude.api-key", "pulseops.github.token");

	/** 기동과 함께 등록되므로 아무 일이 일어나지 않아도 첫 scrape에 나온다. */
	private static final List<String> EXPECTED_CUSTOM_METRICS = List.of("pulseops_incidents_active",
			"pulseops_incidents_opened_total", "pulseops_incidents_resolved_total",
			"pulseops_telemetry_events_processed_total", "pulseops_telemetry_events_failed_total",
			"pulseops_telemetry_events_rejected_total", "pulseops_kafka_consumer_lag",
			"pulseops_kafka_consumer_partitions_uninitialized", "pulseops_kafka_consumer_lag_snapshot_age_seconds");

	/**
	 * partition별 줄은 offset을 한 번 읽어 온 뒤에야 생긴다. Topic이 몇 개의 partition을 갖는지는 읽어
	 * 보기 전에는 알 수 없고, 모르는 채로 줄을 만들면 있지도 않은 partition을 보고하게 된다.
	 */
	private static final String EXPECTED_PARTITION_METRIC = "pulseops_kafka_consumer_partition_lag";

	/**
	 * 다시 읽기는 scrape를 붙들지 않기 위해 다른 Thread에서 일어난다. 그래서 몇 번째 scrape에 값이
	 * 붙는지는 정해져 있지 않고, 그 시점을 기다리는 대신 몇 번 더 긁어 본다.
	 */
	private static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(10);

	private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

	/** Micrometer가 이미 주는 것을 같은 뜻의 custom metric으로 다시 만들지 않았다는 것도 함께 뜻한다. */
	private static final List<String> EXPECTED_FRAMEWORK_METRICS = List.of("http_server_requests_seconds_bucket",
			"jvm_memory_used_bytes", "jvm_threads_live_threads", "process_cpu_usage", "system_cpu_usage");

	@Autowired
	private MockMvc mockMvc;

	@Test
	void servesTheScrapeEndpoint() throws Exception {
		this.mockMvc.perform(get(PROMETHEUS_PATH)).andExpect(status().isOk());
	}

	/**
	 * Prometheus가 target의 생사를 판단할 자리는 열어 둔다.
	 * <p>
	 * UP을 요구하지 않는다. 이 Context에는 Redis가 없어 health는 정직하게 DOWN으로 답하며, 그것은 이
	 * endpoint가 열려 있고 실제 상태를 말한다는 증거이다. 여기서 UP을 요구하면 테스트를 통과시키기
	 * 위해 health가 무엇을 보는지를 좁히게 된다.
	 */
	@Test
	void servesHealth() throws Exception {
		this.mockMvc.perform(get("/actuator/health")).andExpect(jsonPath("$.status").exists());
	}

	@Test
	void keepsEveryOtherActuatorEndpointClosed() throws Exception {
		for (String path : ENDPOINTS_THAT_MUST_STAY_CLOSED) {
			this.mockMvc.perform(get(path)).andExpect(status().isNotFound());
		}
	}

	@Test
	void publishesTheIncidentTelemetryAndConsumerLagMetrics() throws Exception {
		String body = scrape();

		assertThat(body).contains(EXPECTED_CUSTOM_METRICS);
	}

	@Test
	void publishesLagPerPartitionOnceTheOffsetsHaveBeenRead() {
		await().atMost(REFRESH_TIMEOUT)
			.pollInterval(POLL_INTERVAL)
			.untilAsserted(() -> assertThat(scrape()).contains(EXPECTED_PARTITION_METRIC));
	}

	@Test
	void publishesTheRuntimeMetricsMicrometerAlreadyProvides() throws Exception {
		this.mockMvc.perform(get("/api/incidents/active")).andExpect(status().isOk());

		assertThat(scrape()).contains(EXPECTED_FRAMEWORK_METRICS);
	}

	@Test
	void doesNotLabelAnyMetricWithAPerRequestValue() throws Exception {
		String body = scrape();

		assertThat(body).doesNotContain(FORBIDDEN_LABEL_NAMES);
	}

	@Test
	void doesNotLeakCredentialsThroughMetrics() throws Exception {
		String body = scrape();

		assertThat(body).doesNotContain(SECRETS_THAT_MUST_NOT_APPEAR);
	}

	private String scrape() throws Exception {
		return this.mockMvc.perform(get(PROMETHEUS_PATH))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

}

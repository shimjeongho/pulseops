package com.pulseops.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseops.MySqlTestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 Redis에 기록된 Metric이 Dashboard가 읽는 형태로 나오는지 끝에서 끝까지 확인한다.
 * <p>
 * 저장소를 흉내 내지 않는다. 이 API의 값은 Redis에 남은 Bucket에서 나오고, 계열 목록도 같은 write가
 * 남긴 registry에서 나온다. 그 두 경로가 실제로 이어져 있는지가 이 단계에서 확인할 것이다.
 * <p>
 * 기준 시각을 고정하지 않는다. Controller는 요청을 받은 순간을 쓰므로, 지금 Bucket에 Event를 넣고
 * 그것이 현재 창에 들어오는지를 본다. 창의 경계를 정확한 값으로 비교하는 대신 창이 Event를 담고
 * 있는지로 확인하면 실행 시각에 흔들리지 않는다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class MetricApiIntegrationTest {

	private static final String REDIS_IMAGE = "redis:8.4.2-alpine";

	private static final int REDIS_PORT = 6379;

	private static final List<String> TOP_LEVEL_FIELDS = List.of("generatedAt", "items");

	private static final List<String> ITEM_FIELDS = List.of("service", "method", "endpoint", "windowStart",
			"windowEnd", "requestCount", "errorCount", "errorRate", "p95LatencyMs");

	private static final MetricSeries PRODUCTS = new MetricSeries("demo-shop", "GET", "/api/products/{productId}");

	private static final MetricSeries ORDERS = new MetricSeries("demo-shop", "POST", "/api/orders");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RedisMetricStore metricStore;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	/** 계열 목록은 Key 하나에 모이므로 지우지 않으면 앞 테스트의 계열이 다음 응답에 섞인다. */
	@AfterEach
	void removeMetricKeys() {
		this.redisTemplate.delete(this.redisTemplate.keys("pulseops:*"));
	}

	@Test
	void returnsAnEmptyItemListWhenNothingHasBeenObserved() throws Exception {
		JsonNode body = getMetrics();

		assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(TOP_LEVEL_FIELDS);
		assertThat(body.get("items")).isEmpty();
		assertThat(body.get("generatedAt").stringValue()).endsWith("Z");
	}

	@Test
	void returnsTheObservedSeriesWithItsCurrentWindow() throws Exception {
		record(PRODUCTS, false, 380L);

		JsonNode item = getMetrics().get("items").get(0);

		assertThat(item.propertyNames()).containsExactlyInAnyOrderElementsOf(ITEM_FIELDS);
		assertThat(item.get("service").stringValue()).isEqualTo("demo-shop");
		assertThat(item.get("method").stringValue()).isEqualTo("GET");
		assertThat(item.get("endpoint").stringValue()).isEqualTo("/api/products/{productId}");
		assertThat(item.get("requestCount").longValue()).isEqualTo(1L);
		assertThat(item.get("errorCount").longValue()).isZero();
		assertThat(item.get("p95LatencyMs").longValue()).isEqualTo(380L);
	}

	/** endpoint는 Redis Key의 표기가 아니라 원래 Route Template이어야 화면에서 API를 알아볼 수 있다. */
	@Test
	void neverExposesTheRedisKeySpellingOfTheEndpoint() throws Exception {
		record(PRODUCTS, false, 380L);

		String body = this.mockMvc.perform(get("/api/metrics"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).contains("/api/products/{productId}").doesNotContain("_productId_").doesNotContain("pulseops:");
	}

	/** 5xx만 오류로 센다. 자리수는 Incident의 근거 값과 같아야 하므로 소수 둘째 자리까지이다. */
	@Test
	void reportsErrorRateAsANumberWithTwoDecimalPlaces() throws Exception {
		record(PRODUCTS, true, 500L);
		record(PRODUCTS, false, 100L);
		record(PRODUCTS, false, 100L);
		record(PRODUCTS, false, 100L);

		JsonNode item = getMetrics().get("items").get(0);

		assertThat(item.get("errorRate").isNumber()).isTrue();
		assertThat(item.get("errorRate").decimalValue()).isEqualByComparingTo("25.00");
		assertThat(item.get("errorCount").longValue()).isEqualTo(1L);
		assertThat(item.get("requestCount").longValue()).isEqualTo(4L);
	}

	/** 표본이 없어 구하지 못한 P95는 null이며 0으로 바뀌지 않는다. */
	@Test
	void reportsAMissingP95AsNull() throws Exception {
		record(PRODUCTS, false, 380L);
		// 창을 벗어난 Bucket만 남기면 요청 수와 표본이 함께 사라져 P95만 비는 상태를 만들 수 없다.
		// 대신 표본 LIST만 지워 "요청은 있었는데 응답 시간을 재지 못한 창"을 만든다.
		this.redisTemplate.delete(this.redisTemplate.keys("pulseops:metric:*:latency"));

		JsonNode item = getMetrics().get("items").get(0);

		assertThat(item.get("p95LatencyMs").isNull()).isTrue();
		assertThat(item.get("requestCount").longValue()).isEqualTo(1L);
	}

	/** 값이 아니라 이름으로 정렬해야 다시 읽을 때 줄의 위치가 바뀌지 않는다. */
	@Test
	void ordersItemsByServiceThenEndpointThenMethod() throws Exception {
		record(PRODUCTS, false, 380L);
		record(ORDERS, false, 120L);

		JsonNode items = getMetrics().get("items");

		assertThat(items.get(0).get("endpoint").stringValue()).isEqualTo("/api/orders");
		assertThat(items.get(1).get("endpoint").stringValue()).isEqualTo("/api/products/{productId}");
	}

	private JsonNode getMetrics() throws Exception {
		String body = this.mockMvc.perform(get("/api/metrics"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return this.objectMapper.readTree(body);
	}

	/** 지금 Bucket에 넣어야 Controller가 읽는 현재 창에 들어온다. */
	private void record(MetricSeries series, boolean metricError, long latencyMs) {
		this.metricStore.record(UUID.randomUUID().toString(), series, MetricWindow.bucketStartOf(Instant.now()),
				metricError, latencyMs);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RedisTestcontainersConfiguration {

		@Bean
		@ServiceConnection(name = "redis")
		GenericContainer<?> redisContainer() {
			return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(REDIS_PORT);
		}

	}

}

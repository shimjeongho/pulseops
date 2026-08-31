package com.pulseops.metric;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseops.MySqlTestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 여러 Bucket에 나뉘어 기록된 값이 실제 Redis에서 하나의 60초 Snapshot으로 읽히는지 검증한다.
 * <p>
 * 쓰기 계약을 다시 보는 자리가 아니다. 그 계약은 {@link RedisMetricStoreIntegrationTest}가 고정하고,
 * 여기서는 이미 기록된 것을 읽어 합치는 경로만 본다. 그래서 Bucket을 채우는 데에도 Phase 3-3의 쓰기
 * 경로를 그대로 쓴다. 테스트가 Key를 직접 만들어 넣으면 형식이 어긋나도 이 테스트는 통과한다.
 * <p>
 * Kafka는 띄우지 않는다. 저장된 값을 읽는 테스트가 Broker 유무에 좌우되면 안 된다.
 * <p>
 * MySQL은 Context에 Datasource가 있어 함께 띄운다. {@link MySqlTestcontainersConfiguration} 참고.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(MySqlTestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class RollingMetricServiceIntegrationTest {

	private static final String REDIS_IMAGE = "redis:8.4.2-alpine";

	private static final int REDIS_PORT = 6379;

	private static final MetricSeries SERIES = new MetricSeries("demo-shop", "GET", "/api/orders/{orderId}");

	/** 기록이 하나도 없는 창을 보는 테스트다. 다른 테스트가 채운 Bucket이 섞이면 확인할 것이 사라진다. */
	private static final MetricSeries UNTOUCHED_SERIES = new MetricSeries("demo-shop", "POST", "/api/payments");

	private static final Instant NOW = Instant.parse("2026-08-31T12:34:56.789Z");

	private static final Instant WINDOW_START = Instant.parse("2026-08-31T12:34:00Z");

	private static final Instant WINDOW_END = Instant.parse("2026-08-31T12:35:00Z");

	/** 창 바로 앞의 Bucket이다. 만료 전이라 Redis에는 남아 있지만 이번 창의 값은 아니다. */
	private static final Instant BUCKET_BEFORE_WINDOW = WINDOW_START.minusSeconds(10);

	@Autowired
	private RollingMetricService rollingMetricService;

	@Autowired
	private RedisMetricStore metricStore;

	@Autowired
	private StringRedisTemplate redisTemplate;

	/** 자기가 만든 Key만 지운다. 전체를 비우면 같은 Redis를 쓰는 다른 상태까지 함께 사라진다. */
	private final Set<String> writtenKeys = new HashSet<>();

	@AfterEach
	void removeWrittenKeys() {
		this.redisTemplate.delete(this.writtenKeys);
	}

	/**
	 * 창 안의 여섯 Bucket만 합친다. 세 번째 Bucket은 비워 둬 그 10초에 요청이 없어도 창이 끊기지 않는지
	 * 함께 본다. 창 앞 Bucket에는 창 안 어느 값보다 큰 응답 시간을 넣어, 섞였다면 요청 수뿐 아니라
	 * P95까지 달라지게 했다.
	 */
	@Test
	void aggregatesOnlyTheBucketsInsideTheWindow() {
		seedBucket(WINDOW_START, 1, 10, 1);
		seedBucket(WINDOW_START.plusSeconds(10), 11, 30, 2);
		seedBucket(WINDOW_START.plusSeconds(30), 31, 35, 1);
		seedBucket(WINDOW_START.plusSeconds(40), 36, 45, 0);
		seedBucket(WINDOW_START.plusSeconds(50), 46, 50, 1);
		seedBucket(BUCKET_BEFORE_WINDOW, 9999, 10001, 3);

		MetricSnapshot snapshot = this.rollingMetricService.snapshot(SERIES, NOW);

		assertThat(this.redisTemplate.hasKey(countsKey(SERIES, BUCKET_BEFORE_WINDOW))).isTrue();
		assertThat(snapshot.requestCount()).isEqualTo(50);
		assertThat(snapshot.errorCount()).isEqualTo(5);
		assertThat(snapshot.errorRate()).isEqualTo(10.0);
		assertThat(snapshot.p95LatencyMs()).isEqualTo(48L);
		assertThat(snapshot.windowStart()).isEqualTo(WINDOW_START);
		assertThat(snapshot.windowEnd()).isEqualTo(WINDOW_END);
		assertThat(Duration.between(snapshot.windowStart(), snapshot.windowEnd())).isEqualTo(Duration.ofSeconds(60));
	}

	/**
	 * Bucket이 하나도 없는 것은 아직 요청이 없었다는 뜻이지 오류가 아니다. P95만 null인 것은 대신 쓸
	 * 응답 시간이 없기 때문이며, 0ms는 그 시간에 응답했다는 거짓이 된다.
	 */
	@Test
	void reportsAnEmptyWindowWhenNothingWasRecorded() {
		MetricSnapshot snapshot = this.rollingMetricService.snapshot(UNTOUCHED_SERIES, NOW);

		assertThat(snapshot.requestCount()).isZero();
		assertThat(snapshot.errorCount()).isZero();
		assertThat(snapshot.errorRate()).isEqualTo(0.0);
		assertThat(snapshot.p95LatencyMs()).isNull();
		assertThat(snapshot.windowStart()).isEqualTo(WINDOW_START);
		assertThat(snapshot.windowEnd()).isEqualTo(WINDOW_END);
	}

	/**
	 * 한 Bucket을 응답 시간 {@code firstLatencyMs}부터 {@code lastLatencyMs}까지의 요청으로 채우고,
	 * 그 중 {@code errorCount}건을 5xx로 기록한다. 응답 시간을 겹치지 않게 나눠 두면 어느 Bucket의
	 * 표본이 창에 들어왔는지 P95만 보고도 알 수 있다.
	 */
	private void seedBucket(Instant bucketStart, long firstLatencyMs, long lastLatencyMs, int errorCount) {
		int remainingErrors = errorCount;
		for (long latencyMs = firstLatencyMs; latencyMs <= lastLatencyMs; latencyMs++) {
			boolean metricError = remainingErrors > 0;
			if (metricError) {
				remainingErrors--;
			}
			String eventId = UUID.randomUUID().toString();
			this.writtenKeys.add("pulseops:dedup:event:" + eventId);
			this.writtenKeys.add(countsKey(SERIES, bucketStart));
			this.writtenKeys.add(metricKey(SERIES, bucketStart) + ":latency");
			this.metricStore.record(eventId, SERIES, bucketStart, metricError, latencyMs);
		}
	}

	private static String countsKey(MetricSeries series, Instant bucketStart) {
		return metricKey(series, bucketStart) + ":counts";
	}

	private static String metricKey(MetricSeries series, Instant bucketStart) {
		return "pulseops:metric:" + series.service() + ":" + series.method() + ":" + series.endpointKey() + ":"
				+ bucketStart.getEpochSecond();
	}

	/**
	 * Phase 3-1의 Redis Container 구성을 그대로 쓴다. 공용 Test Configuration으로 빼면 이미 통과하고
	 * 있는 Phase 3-1 테스트까지 함께 바꿔야 하므로, 같은 방식을 따르되 각 테스트가 자기 Container를
	 * 갖는다. {@code name}이 없으면 GenericContainer가 Redis로 인식되지 않아 연결 정보가 Context에
	 * 등록되지 않는다.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class RedisTestcontainersConfiguration {

		@Bean
		@ServiceConnection(name = "redis")
		GenericContainer<?> redisContainer() {
			return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(REDIS_PORT);
		}

	}

}

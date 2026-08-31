package com.pulseops.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Event 하나를 기록했을 때 실제 Redis에 무엇이 남는지 검증한다.
 * <p>
 * 확인 대상은 Java 호출이 아니라 Redis에 남은 결과다. Key 형식, 두 counter, 표본 개수, 만료 시간,
 * 그리고 같은 Event를 두 번 보내도 한 번만 반영된다는 계약이다. 이 계약이 깨지면 Metric이 조용히
 * 부풀거나 창에서 사라지므로 실제 서버로 확인한다.
 * <p>
 * Kafka는 띄우지 않는다. 저장 결과를 보는 테스트가 Broker 유무에 좌우되면 안 된다.
 * <p>
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class RedisMetricStoreIntegrationTest {

	private static final String REDIS_IMAGE = "redis:8.4.2-alpine";

	private static final int REDIS_PORT = 6379;

	private static final MetricSeries SERIES = new MetricSeries("demo-shop", "GET", "/api/products/{productId}");

	/**
	 * 테스트마다 다른 Bucket을 쓴다. 같은 Bucket을 나눠 쓰면 한 테스트가 올린 counter가 다른 테스트의
	 * 기대값에 섞여 실행 순서에 묶인다.
	 */
	private static final Instant FIRST_EVENT_BUCKET = Instant.parse("2026-08-31T12:00:00Z");

	private static final Instant SERVER_ERROR_BUCKET = Instant.parse("2026-08-31T12:00:10Z");

	private static final Instant DUPLICATE_BUCKET = Instant.parse("2026-08-31T12:00:20Z");

	private static final Instant DEDUP_TTL_BUCKET = Instant.parse("2026-08-31T12:00:30Z");

	private static final Instant TWO_EVENTS_BUCKET = Instant.parse("2026-08-31T12:00:40Z");

	private static final Instant TTL_BUCKET = Instant.parse("2026-08-31T12:00:50Z");

	private static final Instant SAMPLE_CAP_BUCKET = Instant.parse("2026-08-31T12:01:00Z");

	private static final Instant KEY_FORMAT_BUCKET = Instant.parse("2026-08-31T12:01:10Z");

	private static final Instant REJECTED_ARGUMENT_BUCKET = Instant.parse("2026-08-31T12:01:20Z");

	private static final String REQUEST_COUNT_FIELD = "requestCount";

	private static final String ERROR_COUNT_FIELD = "errorCount";

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

	@Test
	void writesBothCountersAndTheLatencySampleForANewEvent() {
		boolean applied = record(newEventId(), FIRST_EVENT_BUCKET, false, 37L);

		assertThat(applied).isTrue();
		assertThat(counts(FIRST_EVENT_BUCKET, REQUEST_COUNT_FIELD)).isEqualTo("1");
		assertThat(counts(FIRST_EVENT_BUCKET, ERROR_COUNT_FIELD)).isEqualTo("0");
		assertThat(latencySamples(FIRST_EVENT_BUCKET)).containsExactly("37");
	}

	@Test
	void countsAServerErrorInBothCounters() {
		record(newEventId(), SERVER_ERROR_BUCKET, true, 480L);

		assertThat(counts(SERVER_ERROR_BUCKET, REQUEST_COUNT_FIELD)).isEqualTo("1");
		assertThat(counts(SERVER_ERROR_BUCKET, ERROR_COUNT_FIELD)).isEqualTo("1");
	}

	/**
	 * Kafka는 같은 Event를 다시 전달한다. 두 번째 호출이 counter나 표본을 건드리면 한 번 일어난 요청이
	 * 두 번 일어난 것으로 남는다.
	 */
	@Test
	void appliesTheSameEventIdOnlyOnce() {
		String eventId = newEventId();

		boolean firstApplied = record(eventId, DUPLICATE_BUCKET, false, 37L);
		boolean secondApplied = record(eventId, DUPLICATE_BUCKET, false, 37L);

		assertThat(firstApplied).isTrue();
		assertThat(secondApplied).isFalse();
		assertThat(counts(DUPLICATE_BUCKET, REQUEST_COUNT_FIELD)).isEqualTo("1");
		assertThat(latencySamples(DUPLICATE_BUCKET)).hasSize(1);
		assertThat(this.redisTemplate.hasKey(dedupKey(eventId))).isTrue();
	}

	/**
	 * 중복 Event가 dedup 표시의 수명을 늘리면 재전달이 이어지는 동안 표시가 사라지지 않는다. 만료를
	 * 기다리는 대신 남은 시간을 짧게 줄여 두고, 다시 기록해도 그 시간이 되돌아가지 않는지 본다.
	 */
	@Test
	void doesNotExtendTheDedupMarkerOnADuplicate() {
		String eventId = newEventId();
		record(eventId, DEDUP_TTL_BUCKET, false, 37L);
		this.redisTemplate.expire(dedupKey(eventId), Duration.ofSeconds(30));

		record(eventId, DEDUP_TTL_BUCKET, false, 37L);

		assertThat(timeToLive(dedupKey(eventId))).isPositive().isLessThanOrEqualTo(30L);
	}

	@Test
	void aggregatesDifferentEventsOfTheSameSeriesAndBucket() {
		record(newEventId(), TWO_EVENTS_BUCKET, false, 37L);
		record(newEventId(), TWO_EVENTS_BUCKET, false, 41L);

		assertThat(counts(TWO_EVENTS_BUCKET, REQUEST_COUNT_FIELD)).isEqualTo("2");
		assertThat(latencySamples(TWO_EVENTS_BUCKET)).containsExactly("37", "41");
	}

	/**
	 * 만료를 기다려서 확인하지 않는다. 확인하려는 것은 만료가 걸렸는지이므로 남은 시간을 보면 충분하다.
	 * Bucket보다 dedup 표시가 오래 남아야, 이미 사라진 Bucket이 재전달 Event 하나로 되살아나지 않는다.
	 */
	@Test
	void expiresMetricBucketsSoonerThanTheDedupMarker() {
		String eventId = newEventId();

		record(eventId, TTL_BUCKET, false, 37L);

		assertThat(timeToLive(countsKey(TTL_BUCKET))).isPositive().isLessThanOrEqualTo(300L);
		assertThat(timeToLive(latencyKey(TTL_BUCKET))).isPositive().isLessThanOrEqualTo(300L);
		assertThat(timeToLive(dedupKey(eventId))).isPositive().isLessThanOrEqualTo(900L);
	}

	/**
	 * 표본이 상한을 넘으면 오래된 쪽을 버린다. 먼저 들어온 500개를 남기면 Bucket이 채워진 직후의 표본이
	 * 그대로 굳어 이후 요청은 P95에 반영되지 않는다. 남은 첫 값이 1이 아니라 2인 것이 그 차이다.
	 */
	@Test
	void keepsOnlyTheMostRecentLatencySamples() {
		for (int latencyMs = 1; latencyMs <= 501; latencyMs++) {
			record(newEventId(), SAMPLE_CAP_BUCKET, false, latencyMs);
		}

		assertThat(counts(SAMPLE_CAP_BUCKET, REQUEST_COUNT_FIELD)).isEqualTo("501");
		assertThat(latencySamples(SAMPLE_CAP_BUCKET)).hasSize(500).startsWith("2").endsWith("501");
	}

	/** Bucket이 갈리지 않으면 60초 창을 읽을 때 어느 10초에 무슨 일이 있었는지 알 수 없다. */
	@Test
	void separatesEventsThatFallIntoDifferentBuckets() {
		Instant earlier = Instant.parse("2026-08-31T12:02:08Z");
		Instant later = earlier.plusSeconds(12);
		Instant earlierBucket = MetricWindow.bucketStartOf(earlier);
		Instant laterBucket = MetricWindow.bucketStartOf(later);

		record(newEventId(), earlierBucket, false, 37L);
		record(newEventId(), laterBucket, false, 41L);

		assertThat(countsKey(earlierBucket)).isNotEqualTo(countsKey(laterBucket));
		assertThat(counts(earlierBucket, REQUEST_COUNT_FIELD)).isEqualTo("1");
		assertThat(counts(laterBucket, REQUEST_COUNT_FIELD)).isEqualTo("1");
	}

	/**
	 * Key 형식은 쓰는 쪽과 읽는 쪽이 함께 쓰는 계약이라 문자열 그대로 고정한다. 저장소의 계산을 불러
	 * 쓰면 형식이 바뀔 때 기대값도 같이 바뀌어 아무것도 고정하지 못한다.
	 */
	@Test
	void usesTheAgreedKeyFormat() {
		String eventId = newEventId();
		long bucketStartEpochSecond = KEY_FORMAT_BUCKET.getEpochSecond();

		record(eventId, KEY_FORMAT_BUCKET, false, 37L);

		assertThat(this.redisTemplate.hasKey(
				"pulseops:metric:demo-shop:GET:/api/products/_productId_:" + bucketStartEpochSecond + ":counts"))
			.isTrue();
		assertThat(this.redisTemplate.hasKey(
				"pulseops:metric:demo-shop:GET:/api/products/_productId_:" + bucketStartEpochSecond + ":latency"))
			.isTrue();
		assertThat(this.redisTemplate.hasKey("pulseops:dedup:event:" + eventId)).isTrue();
	}

	/**
	 * script가 명령 몇 개를 실행한 뒤 멈추면 앞서 실행된 write는 되돌아가지 않는다. 그래서 저장될 값이
	 * 잘못됐다면 Redis에 닿기 전에 걸러야 한다.
	 */
	@Test
	void rejectsABlankEventIdBeforeTouchingRedis() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.metricStore.record(" ", SERIES, REJECTED_ARGUMENT_BUCKET, false, 37L));

		assertThat(this.redisTemplate.hasKey(countsKey(REJECTED_ARGUMENT_BUCKET))).isFalse();
	}

	@Test
	void rejectsANegativeLatencyBeforeTouchingRedis() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.metricStore.record(newEventId(), SERIES, REJECTED_ARGUMENT_BUCKET, false, -1L));

		assertThat(this.redisTemplate.hasKey(countsKey(REJECTED_ARGUMENT_BUCKET))).isFalse();
	}

	/** script에 넘기는 고정 값이다. 표본 수와 만료 시간을 보는 테스트가 무엇을 고정하는지 정한다. */
	@Test
	void fixesThePolicyArgumentsPassedToTheScript() {
		assertThat(RedisMetricStore.SAMPLE_CAP).isEqualTo(500);
		assertThat(RedisMetricStore.METRIC_TTL_SECONDS).isEqualTo(300);
		assertThat(RedisMetricStore.DEDUP_TTL_SECONDS).isEqualTo(900);
	}

	private boolean record(String eventId, Instant bucketStart, boolean metricError, long latencyMs) {
		this.writtenKeys.add(dedupKey(eventId));
		this.writtenKeys.add(countsKey(bucketStart));
		this.writtenKeys.add(latencyKey(bucketStart));
		return this.metricStore.record(eventId, SERIES, bucketStart, metricError, latencyMs);
	}

	private String counts(Instant bucketStart, String field) {
		return (String) this.redisTemplate.opsForHash().get(countsKey(bucketStart), field);
	}

	private List<String> latencySamples(Instant bucketStart) {
		return this.redisTemplate.opsForList().range(latencyKey(bucketStart), 0, -1);
	}

	private long timeToLive(String key) {
		return this.redisTemplate.getExpire(key, TimeUnit.SECONDS);
	}

	private static String countsKey(Instant bucketStart) {
		return metricKey(bucketStart) + ":counts";
	}

	private static String latencyKey(Instant bucketStart) {
		return metricKey(bucketStart) + ":latency";
	}

	private static String metricKey(Instant bucketStart) {
		return "pulseops:metric:demo-shop:GET:/api/products/_productId_:" + bucketStart.getEpochSecond();
	}

	private static String dedupKey(String eventId) {
		return "pulseops:dedup:event:" + eventId;
	}

	private static String newEventId() {
		return UUID.randomUUID().toString();
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

package com.pulseops.metric;

import java.time.Instant;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Event 하나를 Redis의 Metric Bucket에 반영한다.
 * <p>
 * Redis를 아는 유일한 계층이다. Key 형식과 자료 구조, script 실행이 모두 여기 있고 밖에서는
 * "이 Event를 이 Bucket에 넣어라"까지만 말한다. 반대로 어떤 Event를 넣을지 판단하는 일은 하지
 * 않는다. 집계 창 판정과 오류 분류는 {@link MetricRecorder}의 몫이다.
 * <p>
 * 한 Event를 반영하는 데 Redis 왕복은 한 번이다. 명령을 나눠 보내면 dedup 표시와 counter 증가
 * 사이에 같은 Event의 다른 사본이 끼어들어 같은 요청이 두 번 세어질 수 있고, Kafka Consumer
 * Thread가 Redis 응답을 여러 번 기다리게 된다.
 */
@Component
public class RedisMetricStore {

	/**
	 * 한 Bucket에 남기는 latency 표본 수이다. P95를 계산할 만큼은 되면서 Bucket 하나가 무한히
	 * 커지지 않는 선이며, 표본이 넘치면 오래된 쪽을 버린다.
	 * <p>
	 * 이 값과 아래 두 만료 시간은 Phase 3에서 정한 정책이라 설정으로 빼지 않는다. 환경마다 달라지면
	 * 같은 Metric이 환경마다 다른 뜻을 갖는다.
	 */
	static final int SAMPLE_CAP = 500;

	/** Bucket이 Redis에 남는 시간이다. 60초 창을 읽는 데 필요한 시간보다 넉넉해야 한다. */
	static final long METRIC_TTL_SECONDS = 300;

	/**
	 * dedup 표시가 남는 시간이다. Bucket보다 길게 잡아, Bucket이 사라진 뒤에 도착한 재전달
	 * Event가 이미 만료된 Bucket을 다시 만들지 않게 한다.
	 */
	static final long DEDUP_TTL_SECONDS = 900;

	private static final String SCRIPT_LOCATION = "redis/metric-update.lua";

	private static final String METRIC_KEY_PREFIX = "pulseops:metric:";

	private static final String DEDUP_KEY_PREFIX = "pulseops:dedup:event:";

	private static final String COUNTS_KEY_SUFFIX = ":counts";

	private static final String LATENCY_KEY_SUFFIX = ":latency";

	private static final String KEY_SEPARATOR = ":";

	/** script가 반영을 뜻해 돌려주는 값이다. 비교로 확인해 응답이 없는 경우까지 unboxing에 기대지 않는다. */
	private static final Long APPLIED = 1L;

	private final StringRedisTemplate redisTemplate;

	private final RedisScript<Long> metricUpdateScript;

	public RedisMetricStore(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
		this.metricUpdateScript = metricUpdateScript();
	}

	/**
	 * script를 한 번만 구성해 두고 재사용한다. Spring Data Redis가 sha1로 먼저 실행하고 서버가
	 * script를 잊었을 때만 본문을 다시 보내므로, 직접 sha를 관리하지 않는다.
	 */
	private static RedisScript<Long> metricUpdateScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource(SCRIPT_LOCATION));
		script.setResultType(Long.class);
		return script;
	}

	/**
	 * Bucket에 Event를 반영하고, 이번 호출이 실제로 Metric을 바꿨으면 true를 반환한다. 이미 같은
	 * eventId가 반영돼 있었으면 아무것도 바꾸지 않고 false다.
	 * <p>
	 * script에 넘기는 값이 잘못되면 명령 몇 개를 실행한 뒤 script가 멈출 수 있고, 그때 앞선 write는
	 * 되돌아가지 않는다. 그래서 저장되는 값이 되는 두 인자를 호출 전에 확인한다. 나머지는 확인할
	 * 것이 없다. errorDelta는 boolean에서 만들어지므로 0이나 1 외의 값이 될 수 없고, 표본 수와 TTL은
	 * 상수이며, series와 bucketStart가 null이면 Key를 만드는 자리에서 먼저 실패한다.
	 * <p>
	 * Event 계약 검증은 여기서 하지 않는다. Kafka로 들어온 Event는 이미 TelemetryEventValidator가
	 * 걸러 냈고, 같은 검증을 두 곳에 두면 계약이 갈라진다.
	 */
	public boolean record(String eventId, MetricSeries series, Instant bucketStart, boolean metricError,
			long latencyMs) {
		if (eventId == null || eventId.isBlank()) {
			throw new IllegalArgumentException("eventId가 비어 있다");
		}
		if (latencyMs < 0) {
			throw new IllegalArgumentException("latencyMs가 음수다: " + latencyMs);
		}
		List<String> keys = List.of(dedupKey(eventId), metricKey(series, bucketStart, COUNTS_KEY_SUFFIX),
				metricKey(series, bucketStart, LATENCY_KEY_SUFFIX));
		Long applied = this.redisTemplate.execute(this.metricUpdateScript, keys, metricError ? "1" : "0",
				Long.toString(latencyMs), Integer.toString(SAMPLE_CAP), Long.toString(METRIC_TTL_SECONDS),
				Long.toString(DEDUP_TTL_SECONDS));
		return APPLIED.equals(applied);
	}

	/**
	 * Bucket은 시작 시각의 epoch second로 표시한다. 사람이 읽기 좋은 형식은 표기법과 시간대에 따라
	 * 갈라지지만 epoch second는 하나뿐이라, 쓰는 쪽과 읽는 쪽이 같은 Key를 만든다.
	 */
	private String metricKey(MetricSeries series, Instant bucketStart, String suffix) {
		return METRIC_KEY_PREFIX + series.service() + KEY_SEPARATOR + series.method() + KEY_SEPARATOR
				+ series.endpointKey() + KEY_SEPARATOR + bucketStart.getEpochSecond() + suffix;
	}

	/** Metric Key와 다른 접두사를 쓴다. 수명과 목적이 달라 운영 중에 섞여 보이면 안 된다. */
	private String dedupKey(String eventId) {
		return DEDUP_KEY_PREFIX + eventId;
	}

}

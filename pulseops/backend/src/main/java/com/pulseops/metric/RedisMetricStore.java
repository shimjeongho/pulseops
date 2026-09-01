package com.pulseops.metric;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
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
 * <p>
 * 읽는 쪽도 마찬가지로 어느 Bucket을 읽을지만 밖에서 받고, 읽어 온 숫자를 그대로 돌려준다. Error
 * Rate와 P95는 {@link MetricSnapshot}의 몫이라 여기서 계산하지 않는다.
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

	/**
	 * 관측된 계열을 모아 두는 ZSET이다. Dashboard가 "지금 어떤 계열이 있는가"를 알아야 하는데, Metric
	 * Key에서 그 답을 얻을 수는 없다. Key의 endpoint 자리는 {@link MetricSeries#endpointKey()}를 거쳐
	 * 중괄호가 밑줄로 바뀐 뒤라 원래 Route Template으로 되돌릴 수 없고, Key를 훑는 방식은 수명이 훨씬
	 * 긴 dedup Key까지 함께 지나야 해서 Event 수에 비례해 비싸진다.
	 * <p>
	 * SET이 아니라 ZSET인 이유는 member마다 만료를 걸 수 없기 때문이다. 마지막으로 관측된 Bucket을
	 * score로 두면 읽는 쪽이 오래된 계열을 걸러 낼 수 있어, 따로 정리하는 일 없이 수명이 정해진다.
	 */
	static final String SERIES_REGISTRY_KEY = "pulseops:metric:series";

	/**
	 * registry member는 {@code service|method|endpoint}이며 endpoint는 Key와 달리 원래 Route
	 * Template 그대로다. 구분자로 세로줄을 쓰는 것은 세 값 중 어디에도 나타날 수 없는 문자이기
	 * 때문이다. Route Template에는 URL 경로에 쓰이는 문자만 들어온다.
	 */
	private static final String MEMBER_SEPARATOR = "|";

	/**
	 * 세로줄은 정규식에서 선택을 뜻하므로 그대로 넘기면 문자 하나가 아니라 빈 대안으로 읽힌다. 구분자에서
	 * 직접 pattern을 만들어 둘이 갈라지지 않게 하고, 미리 compile해 호출마다 다시 분석하지 않는다.
	 */
	private static final Pattern MEMBER_SEPARATOR_PATTERN = Pattern.compile(Pattern.quote(MEMBER_SEPARATOR));

	private static final int MEMBER_PART_COUNT = 3;

	private static final String DEDUP_KEY_PREFIX = "pulseops:dedup:event:";

	private static final String COUNTS_KEY_SUFFIX = ":counts";

	private static final String LATENCY_KEY_SUFFIX = ":latency";

	/** counts HASH의 두 field이다. HMGET에 넘기는 순서가 곧 결과에서 읽는 순서다. */
	private static final String REQUEST_COUNT_FIELD = "requestCount";

	private static final String ERROR_COUNT_FIELD = "errorCount";

	private static final int REQUEST_COUNT_RESULT_INDEX = 0;

	private static final int ERROR_COUNT_RESULT_INDEX = 1;

	/** Bucket 하나를 읽는 데 HMGET과 LRANGE를 보낸다. pipeline 결과에서 Bucket의 자리를 찾는 데 쓴다. */
	private static final int COMMANDS_PER_BUCKET = 2;

	private static final String KEY_SEPARATOR = ":";

	/** script가 반영을 뜻해 돌려주는 값이다. 비교로 확인해 응답이 없는 경우까지 unboxing에 기대지 않는다. */
	private static final Long APPLIED = 1L;

	private static final Logger logger = LoggerFactory.getLogger(RedisMetricStore.class);

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
				metricKey(series, bucketStart, LATENCY_KEY_SUFFIX), SERIES_REGISTRY_KEY);
		Long applied = this.redisTemplate.execute(this.metricUpdateScript, keys, metricError ? "1" : "0",
				Long.toString(latencyMs), Integer.toString(SAMPLE_CAP), Long.toString(METRIC_TTL_SECONDS),
				Long.toString(DEDUP_TTL_SECONDS), seriesMember(series), Long.toString(bucketStart.getEpochSecond()));
		return APPLIED.equals(applied);
	}

	/**
	 * 창에 속한 Bucket들을 읽어 하나로 합친 원본 집계값을 돌려준다.
	 * <p>
	 * 어느 Bucket을 읽을지는 호출자가 정한다. 창을 나누는 규칙이 {@link MetricWindow} 한 곳에만
	 * 있어야 쓰는 쪽과 읽는 쪽의 경계가 갈라지지 않는다. 여기서는 받은 Bucket만 정확히 읽으며 Key를
	 * 훑어 최근 Bucket을 찾지 않는다. 만료를 아직 기다리는 창 밖 Bucket이 그런 식으로 섞여 들어오면
	 * 창의 길이가 그때그때 달라진다.
	 * <p>
	 * Bucket마다 명령이 둘이라 창 하나에 왕복이 열두 번이 되므로 pipeline으로 한 번에 보낸다.
	 * pipeline은 명령을 묶어 보낼 뿐 원자적으로 실행하지 않는다. 읽는 도중 들어온 write가 앞쪽
	 * Bucket에는 반영되고 뒤쪽에는 반영되지 않을 수 있지만, 60초 창에서 요청 한두 건의 차이는 Error
	 * Rate나 P95의 뜻을 바꾸지 않는다. 반대로 읽기를 원자적으로 만들면 그동안 Metric을 쓰는 Kafka
	 * Consumer가 기다리게 된다.
	 * <p>
	 * 아직 요청이 없어 Bucket이 없는 것은 정상이며 0과 빈 표본으로 읽는다. Redis 장애는 그것과 뜻이
	 * 달라 예외를 그대로 올려 보낸다. 여기서 삼켜 0으로 만들면 읽는 쪽이 장애를 트래픽 없음으로 본다.
	 */
	public MetricWindowData read(MetricSeries series, List<Instant> bucketStarts) {
		List<Object> pipelineResults = this.redisTemplate.executePipelined((RedisCallback<Object>) (connection) -> {
			for (Instant bucketStart : bucketStarts) {
				connection.hashCommands()
					.hMGet(toBytes(metricKey(series, bucketStart, COUNTS_KEY_SUFFIX)), toBytes(REQUEST_COUNT_FIELD),
							toBytes(ERROR_COUNT_FIELD));
				connection.listCommands().lRange(toBytes(metricKey(series, bucketStart, LATENCY_KEY_SUFFIX)), 0, -1);
			}
			// pipeline 안에서는 명령의 결과가 여기로 돌아오지 않는다. 결과는 실행이 끝난 뒤 보낸
			// 순서대로 한꺼번에 주어지며, 여기서 값을 돌려주면 Template이 잘못된 사용으로 막는다.
			return null;
		});
		return aggregate(pipelineResults, bucketStarts.size());
	}

	/**
	 * 지금 Dashboard에 보여 줄 계열을 돌려준다.
	 * <p>
	 * 기준 시각에서 {@link #METRIC_TTL_SECONDS}만큼 앞선 Bucket까지만 살펴본다. 그보다 오래된 계열은
	 * Bucket이 이미 만료돼 어떤 창으로도 읽히지 않으므로, 남겨 두면 언제나 0인 줄이 된다.
	 * <p>
	 * 경계를 Bucket 시작으로 내림하는 것이 중요하다. score는 Bucket 시작이라 벽시계 시각과 그대로
	 * 비교하면 같은 순간을 서로 다른 눈금으로 재게 된다. {@link MetricRecorder}는 {@code now - 300}에
	 * 일어난 Event까지 받아들이는데 그 Event의 score는 자기 Bucket의 시작이라 언제나 그 시각보다
	 * 앞서므로, 내림하지 않으면 방금 기록된 계열이 그 자리에서 걸러진다.
	 * <p>
	 * 계열이 없거나 Key가 이미 사라진 것은 정상이며 빈 목록이다. Redis 장애는 뜻이 달라 예외를 그대로
	 * 올려 보낸다. 여기서 빈 목록으로 바꾸면 읽는 쪽이 장애를 "관측 중인 API 없음"으로 본다.
	 */
	public List<MetricSeries> activeSeries(Instant now) {
		Instant cutoff = MetricWindow.bucketStartOf(now.minusSeconds(METRIC_TTL_SECONDS));
		Set<String> members = this.redisTemplate.opsForZSet()
			.rangeByScore(SERIES_REGISTRY_KEY, cutoff.getEpochSecond(), Double.POSITIVE_INFINITY);
		if (members == null) {
			return List.of();
		}
		List<MetricSeries> activeSeries = new ArrayList<>();
		for (String member : members) {
			String[] parts = MEMBER_SEPARATOR_PATTERN.split(member, MEMBER_PART_COUNT);
			// 이 registry를 쓰는 것은 이 Class뿐이라 형식이 어긋날 일이 없다. 그래도 걸러 내는 이유는
			// 손상된 값 하나 때문에 나머지 계열까지 보이지 않으면 안 되기 때문이고, 걸러 낸 사실은
			// 조용히 묻지 않는다.
			if (parts.length != MEMBER_PART_COUNT) {
				logger.warn("형식이 어긋난 Metric 계열 등록을 건너뛴다. member={}", member);
				continue;
			}
			activeSeries.add(new MetricSeries(parts[0], parts[1], parts[2]));
		}
		return List.copyOf(activeSeries);
	}

	/**
	 * pipeline 결과는 명령을 보낸 순서 그대로 돌아온다. Bucket마다 HMGET 다음에 LRANGE를 보냈으므로
	 * 같은 순서로 읽는다.
	 * <p>
	 * 표본은 정렬하지도 잘라 내지도 않고 Bucket 순서대로 이어 붙인다. 창 전체의 표본이 필요한 계산은
	 * P95뿐이고 그 계산은 순서를 쓰지 않는다.
	 */
	private static MetricWindowData aggregate(List<Object> pipelineResults, int bucketCount) {
		long requestCount = 0;
		long errorCount = 0;
		List<Long> latencySamples = new ArrayList<>();
		for (int bucketIndex = 0; bucketIndex < bucketCount; bucketIndex++) {
			int countsResultIndex = bucketIndex * COMMANDS_PER_BUCKET;
			List<String> counts = stringsAt(pipelineResults, countsResultIndex);
			requestCount += count(counts, REQUEST_COUNT_RESULT_INDEX);
			errorCount += count(counts, ERROR_COUNT_RESULT_INDEX);
			for (String latencySample : stringsAt(pipelineResults, countsResultIndex + 1)) {
				latencySamples.add(Long.valueOf(latencySample));
			}
		}
		return new MetricWindowData(requestCount, errorCount, latencySamples);
	}

	/**
	 * Template은 pipeline 결과의 문자열을 이미 되돌려 놓는다. Bucket이 없으면 Redis가 값 대신 없음을
	 * 돌려주므로 그 자리가 비어 있을 수 있다.
	 */
	@SuppressWarnings("unchecked")
	private static List<String> stringsAt(List<Object> pipelineResults, int resultIndex) {
		Object pipelineResult = pipelineResults.get(resultIndex);
		return (pipelineResult != null) ? (List<String>) pipelineResult : List.of();
	}

	/** Bucket이나 field가 없는 것은 그 10초에 요청이 없었다는 뜻이므로 0으로 읽는다. */
	private static long count(List<String> counts, int fieldIndex) {
		if (fieldIndex >= counts.size()) {
			return 0L;
		}
		String count = counts.get(fieldIndex);
		return (count != null) ? Long.parseLong(count) : 0L;
	}

	/**
	 * Key와 field를 bytes로 바꾼다. pipeline에서는 Template의 Type별 연산 대신 연결에 직접 명령을
	 * 보내므로, Template이 Key를 만들 때 쓰는 것과 같은 방식을 그대로 빌려 쓴다. 문자 Encoding을 여기서
	 * 따로 정하면 쓰는 쪽과 읽는 쪽의 Key가 달라질 수 있다.
	 */
	private byte[] toBytes(String value) {
		return this.redisTemplate.getStringSerializer().serialize(value);
	}

	/**
	 * registry에 넣을 계열 식별자이다. Key와 달리 endpoint를 Route Template 그대로 둔다. Key 쪽 표기는
	 * 중괄호가 밑줄로 바뀌어 되돌릴 수 없어, 읽는 쪽이 원래 endpoint를 복원할 수 없다.
	 */
	private static String seriesMember(MetricSeries series) {
		return series.service() + MEMBER_SEPARATOR + series.method() + MEMBER_SEPARATOR + series.endpoint();
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

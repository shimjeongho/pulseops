package com.pulseops.metric;

import com.pulseops.telemetry.TelemetryEvent;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Telemetry Event 하나를 실시간 Metric에 반영한다.
 * <p>
 * Event를 집계에 넣을지 판단하고 어떤 Bucket에 어떤 값으로 넣을지 정하는 곳이다. 저장 방식은
 * {@link RedisMetricStore}가 알고 있어 여기서는 Key도 script도 다루지 않는다.
 * <p>
 * Redis 장애를 삼키지 않는다. Phase 3에서는 예외가 나가면 Consumer가 같은 offset에서 멈추기만 했기
 * 때문에 WARN 후 건너뛰었지만, 이제는 Consumer에 제한된 재시도와 DLT가 있어 실패를 올려 보내는 편이
 * 낫다. 그대로 건너뛰면 Redis가 잠깐 흔들린 구간의 Event가 영구히 사라지는 반면, 올려 보내면 몇 초
 * 뒤 재시도가 같은 Event를 다시 반영할 수 있다.
 * <p>
 * 반영 결과는 {@link MetricRecordOutcome}으로 돌려준다. 이 Event로 Metric이 달라졌는지, 그리고
 * 달라지지 않았다면 그것이 재전달 때문인지 창 밖이기 때문인지는 뒤따르는 처리가 알아야 하는
 * 사실이다.
 */
@Component
public class MetricRecorder {

	/**
	 * Metric에서 오류로 세는 경계이다. 5xx는 서비스가 처리에 실패한 것이고 4xx는 요청이 잘못된
	 * 것이라, 4xx를 오류로 세면 Client의 잘못된 호출이 서비스 장애처럼 보인다. 4xx도 요청은
	 * 요청이므로 requestCount에는 들어간다.
	 * <p>
	 * Event의 success를 쓰지 않는다. success는 {@code httpStatus < 400}이라는 다른 경계이며, 그것을
	 * 그대로 쓰면 400이 오류가 된다.
	 */
	private static final int FIRST_ERROR_STATUS = 500;

	private static final Logger logger = LoggerFactory.getLogger(MetricRecorder.class);

	private final RedisMetricStore metricStore;

	public MetricRecorder(RedisMetricStore metricStore) {
		this.metricStore = metricStore;
	}

	/**
	 * 이 Event가 Metric에 어떻게 반영됐는지 돌려준다.
	 * <p>
	 * 현재 시각을 읽는 자리는 이 한 곳뿐이다. 판단에 쓰는 시각을 여러 번 읽으면 한 Event를
	 * 처리하는 동안 기준이 흔들리고, 무엇보다 그 시각을 테스트가 정할 수 없게 된다. Clock을 Bean으로
	 * 두지 않은 것은 Metric 한 곳을 위해 Application 전체에 시간 구성을 만드는 셈이기 때문이다.
	 */
	public MetricRecordOutcome record(TelemetryEvent event) {
		return record(event, Instant.now());
	}

	MetricRecordOutcome record(TelemetryEvent event, Instant now) {
		// 창 밖의 Event는 Redis에 닿기 전에 끝낸다. 이미 사라진 Bucket을 되살리거나 아직 오지 않은
		// 구간에 값을 넣으면 어떤 창에서도 읽히지 않는 Bucket만 남는다. 시각을 지금으로 당겨 맞추면
		// 실제로 일어나지 않은 시각의 Metric을 만드는 것이라 하지 않는다.
		if (!MetricWindow.isWithinAcceptWindow(event.occurredAt(), now)) {
			logger.debug("집계 창 밖의 Telemetry Event를 건너뛴다. eventId={} occurredAt={} now={}", event.eventId(),
					event.occurredAt(), now);
			return MetricRecordOutcome.OUT_OF_WINDOW;
		}
		boolean applied = this.metricStore.record(event.eventId(), MetricSeries.from(event),
				MetricWindow.bucketStartOf(event.occurredAt()), isMetricError(event.httpStatus()), event.latencyMs());
		// 같은 Event가 다시 오는 것은 at-least-once 전달에서 정상이다. 걸러 냈다는 사실만 남긴다.
		if (!applied) {
			logger.debug("이미 반영한 Telemetry Event를 건너뛴다. eventId={}", event.eventId());
			return MetricRecordOutcome.DUPLICATE;
		}
		return MetricRecordOutcome.APPLIED;
	}

	static boolean isMetricError(int httpStatus) {
		return httpStatus >= FIRST_ERROR_STATUS;
	}

}

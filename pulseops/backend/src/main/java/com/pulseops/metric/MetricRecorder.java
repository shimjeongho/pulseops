package com.pulseops.metric;

import com.pulseops.telemetry.TelemetryEvent;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Telemetry Event 하나를 실시간 Metric에 반영한다.
 * <p>
 * Event를 집계에 넣을지 판단하고 어떤 Bucket에 어떤 값으로 넣을지 정하는 곳이다. 저장 방식은
 * {@link RedisMetricStore}가 알고 있어 여기서는 Key도 script도 다루지 않는다.
 * <p>
 * Metric을 남기지 못하는 것은 관측의 손실이지 요청 처리의 실패가 아니다. 그래서 Redis가 응답하지
 * 않거나 이미 반영된 Event가 다시 와도 예외를 밖으로 내보내지 않는다. 예외가 나가면 Kafka
 * Consumer가 같은 offset을 반복해 읽으며 그 자리에 멈춘다.
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
	 * 현재 시각을 읽는 자리는 이 한 곳뿐이다. 판단에 쓰는 시각을 여러 번 읽으면 한 Event를
	 * 처리하는 동안 기준이 흔들리고, 무엇보다 그 시각을 테스트가 정할 수 없게 된다. Clock을 Bean으로
	 * 두지 않은 것은 Metric 한 곳을 위해 Application 전체에 시간 구성을 만드는 셈이기 때문이다.
	 */
	public void record(TelemetryEvent event) {
		record(event, Instant.now());
	}

	void record(TelemetryEvent event, Instant now) {
		// 창 밖의 Event는 Redis에 닿기 전에 끝낸다. 이미 사라진 Bucket을 되살리거나 아직 오지 않은
		// 구간에 값을 넣으면 어떤 창에서도 읽히지 않는 Bucket만 남는다. 시각을 지금으로 당겨 맞추면
		// 실제로 일어나지 않은 시각의 Metric을 만드는 것이라 하지 않는다.
		if (!MetricWindow.isWithinAcceptWindow(event.occurredAt(), now)) {
			logger.debug("집계 창 밖의 Telemetry Event를 건너뛴다. eventId={} occurredAt={} now={}", event.eventId(),
					event.occurredAt(), now);
			return;
		}
		try {
			boolean applied = this.metricStore.record(event.eventId(), MetricSeries.from(event),
					MetricWindow.bucketStartOf(event.occurredAt()), isMetricError(event.httpStatus()),
					event.latencyMs());
			// 같은 Event가 다시 오는 것은 at-least-once 전달에서 정상이다. 걸러 냈다는 사실만 남긴다.
			if (!applied) {
				logger.debug("이미 반영한 Telemetry Event를 건너뛴다. eventId={}", event.eventId());
			}
		}
		// Redis 장애만 흡수한다. RuntimeException까지 잡으면 Metric 계층의 결함이 조용히 묻히고,
		// 잘못 계산된 Metric이 정상처럼 쌓인다. 예외 message에는 Redis가 돌려준 값이 담길 수 있어
		// Type 이름만 남긴다.
		catch (DataAccessException ex) {
			logger.warn("Redis에 Metric을 기록하지 못해 Telemetry Event를 건너뛴다. eventId={} reason={}", event.eventId(),
					ex.getClass().getSimpleName());
		}
	}

	static boolean isMetricError(int httpStatus) {
		return httpStatus >= FIRST_ERROR_STATUS;
	}

}

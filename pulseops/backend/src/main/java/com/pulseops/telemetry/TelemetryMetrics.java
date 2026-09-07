package com.pulseops.telemetry;

import com.pulseops.metric.MetricRecordOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Telemetry Event 소비가 어떤 결과로 끝났는지를 센다.
 * <p>
 * 세기만 한다. 이 Class는 Event를 거르거나 다시 시도하거나 DLT로 보내는 판단에 관여하지 않는다.
 * 재시도 횟수와 DLT 경로는 {@link TelemetryConsumerConfiguration}이, 무엇을 집계에 넣을지는
 * {@code MetricRecorder}가 정하며, 여기를 지워도 그 동작은 그대로다.
 * <p>
 * 결과 이름을 여기서 새로 정의하지 않고 {@link MetricRecordOutcome}을 그대로 쓴다. 관측이 자기만의
 * 분류를 만들면 "Metric에 반영됐다"의 뜻이 두 곳에 생기고, 한쪽만 바뀌면 대시보드의 숫자가 실제
 * 처리와 다른 것을 말하게 된다.
 * <p>
 * Tag에는 eventId도, 계약 위반 사유도, 예외 message도 넣지 않는다. eventId는 Event마다 다르므로
 * 시계열이 트래픽만큼 늘어나고, 나머지 둘은 Event에 실려 온 값이 그대로 담기는 문자열이라 관측
 * 경로가 곧 값 노출 통로가 된다. 무엇이 왜 버려졌는지는 log와 DLT의 원본 Record가 답한다.
 */
@Component
public class TelemetryMetrics {

	static final String PROCESSED_COUNTER = "pulseops.telemetry.events.processed";

	static final String FAILED_COUNTER = "pulseops.telemetry.events.failed";

	static final String REJECTED_COUNTER = "pulseops.telemetry.events.rejected";

	static final String RESULT_TAG = "result";

	private final Counter[] processedCounters;

	private final Counter failedCounter;

	private final Counter rejectedCounter;

	public TelemetryMetrics(MeterRegistry meterRegistry) {
		this.processedCounters = registerProcessedCounters(meterRegistry);
		this.failedCounter = Counter.builder(FAILED_COUNTER)
			.description("Telemetry events whose processing threw and was handed to Kafka retry or the DLT")
			.register(meterRegistry);
		this.rejectedCounter = Counter.builder(REJECTED_COUNTER)
			.description("Telemetry events that violated the event contract and went straight to the DLT")
			.register(meterRegistry);
	}

	/** 처리가 끝난 Event 하나를 그 결과로 센다. */
	public void recordProcessed(MetricRecordOutcome outcome) {
		this.processedCounters[outcome.ordinal()].increment();
	}

	/**
	 * 처리 도중 예외가 나간 Event 하나를 센다.
	 * <p>
	 * 여기 세어진 Event는 사라진 것이 아니다. Kafka의 제한된 재시도가 이어서 같은 Event를 다시
	 * 넘기므로, 재시도가 성공하면 그 Event는 processed에도 한 번 세어진다. 두 Counter의 합이 받은
	 * Event 수와 같지 않은 이유가 이것이다.
	 */
	public void recordFailed() {
		this.failedCounter.increment();
	}

	/** 계약을 어겨 재시도 없이 DLT로 보낸 Event 하나를 센다. */
	public void recordRejected() {
		this.rejectedCounter.increment();
	}

	/**
	 * 있을 수 있는 결과를 모두 미리 등록한다. 처음 일어날 때 만들면, 아직 중복이 한 번도 오지 않은
	 * 구간에서 시계열 자체가 없어 대시보드가 "0건"과 "관측되지 않음"을 구분하지 못한다.
	 */
	private static Counter[] registerProcessedCounters(MeterRegistry meterRegistry) {
		Counter[] counters = new Counter[MetricRecordOutcome.values().length];
		for (MetricRecordOutcome outcome : MetricRecordOutcome.values()) {
			counters[outcome.ordinal()] = Counter.builder(PROCESSED_COUNTER)
				.description("Telemetry events consumed from Kafka, by how they affected the rolling metrics")
				.tag(RESULT_TAG, tagValue(outcome))
				.register(meterRegistry);
		}
		return counters;
	}

	/**
	 * Prometheus의 label 값은 관례상 소문자와 밑줄을 쓴다. enum 이름을 그대로 내보내지 않고 여기서
	 * 한 번만 옮긴다.
	 */
	private static String tagValue(MetricRecordOutcome outcome) {
		return outcome.name().toLowerCase(Locale.ROOT);
	}

}

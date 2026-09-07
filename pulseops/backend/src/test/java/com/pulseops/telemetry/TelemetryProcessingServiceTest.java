package com.pulseops.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import com.pulseops.incident.IncidentDetector;
import com.pulseops.metric.MetricRecordOutcome;
import com.pulseops.metric.MetricRecorder;
import com.pulseops.metric.MetricSeries;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 집계 반영과 이상 판정 사이의 순서와 조건을 저장소 없이 고정한다.
 * <p>
 * 여기서 보는 것은 계산이 아니라 결정이다. 언제 판정을 부르고 언제 부르지 않는지, 그리고 무엇을
 * 넘기는지가 전부다. Redis와 MySQL을 실제로 쓰는 검증은 각 계층의 통합 테스트가 이미 하고 있어,
 * 그것을 여기서 다시 세우면 정작 이 결정이 보이지 않는다.
 */
class TelemetryProcessingServiceTest {

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "GET";

	private static final String ENDPOINT = "/api/products/{productId}";

	private final MetricRecorder metricRecorder = mock(MetricRecorder.class);

	private final IncidentDetector incidentDetector = mock(IncidentDetector.class);

	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private final TelemetryMetrics telemetryMetrics = new TelemetryMetrics(this.meterRegistry);

	private final TelemetryProcessingService processingService = new TelemetryProcessingService(this.metricRecorder,
			this.incidentDetector, this.telemetryMetrics);

	/** 판정 대상 계열은 Event가 가리키는 그 계열이어야 한다. 여기서 어긋나면 다른 API의 상태가 움직인다. */
	@Test
	void detectsOnTheSeriesOfAnEventThatChangedTheMetrics() {
		TelemetryEvent event = event();
		givenMetricOutcome(MetricRecordOutcome.APPLIED);

		this.processingService.process(event);

		ArgumentCaptor<MetricSeries> detected = ArgumentCaptor.forClass(MetricSeries.class);
		then(this.incidentDetector).should().detect(detected.capture(), any());
		assertThat(detected.getValue()).isEqualTo(new MetricSeries(SERVICE, METHOD, ENDPOINT));
	}

	/** 집계 창 밖의 Event는 어떤 창에도 들어가지 않으므로 이어서 확인할 창 자체가 없다. */
	@Test
	void doesNotDetectForAnEventOutsideTheAggregationWindow() {
		givenMetricOutcome(MetricRecordOutcome.OUT_OF_WINDOW);

		this.processingService.process(event());

		then(this.incidentDetector).shouldHaveNoInteractions();
	}

	/**
	 * Phase 7의 핵심이다. 앞선 시도가 Redis 반영까지 끝낸 뒤 판정 도중 실패했다면 재시도로 온 Event는
	 * 중복으로 걸린다. 여기서 멈추면 그 시도가 남긴 미완의 판정을 아무도 이어받지 못하고, 그 Event가
	 * 계열의 마지막이었다면 창은 영영 판정되지 않는다.
	 */
	@Test
	void stillDetectsForARedeliveredEventSoAnUnfinishedEvaluationCanResume() {
		givenMetricOutcome(MetricRecordOutcome.DUPLICATE);

		this.processingService.process(event());

		then(this.incidentDetector).should().detect(any(), any());
	}

	/** 판정 기준 시각은 집계 반영이 끝난 뒤에 정해진다. 반영 여부를 모르는 채로 창을 정할 이유가 없다. */
	@Test
	void picksTheEvaluationInstantAfterTheMetricIsApplied() {
		givenMetricOutcome(MetricRecordOutcome.APPLIED);
		Instant beforeProcessing = Instant.now();

		this.processingService.process(event());

		ArgumentCaptor<Instant> evaluationNow = ArgumentCaptor.forClass(Instant.class);
		then(this.incidentDetector).should().detect(any(), evaluationNow.capture());
		assertThat(evaluationNow.getValue()).isBetween(beforeProcessing, Instant.now());
	}

	/**
	 * 집계 반영이 실패했다는 사실과 Metric 계층 자체가 고장 났다는 사실은 다르다. 후자를 여기서 삼키면
	 * 결함이 log 한 줄 없이 사라지고, 판정이 멈춘 이유를 아무도 알 수 없다.
	 */
	@Test
	void doesNotHideAFailureFromTheMetricRecorder() {
		given(this.metricRecorder.record(any())).willThrow(new IllegalStateException("Metric 계층의 결함"));

		assertThatIllegalStateException().isThrownBy(() -> this.processingService.process(event()));

		then(this.incidentDetector).shouldHaveNoInteractions();
	}

	/** 판정 쪽 실패도 그대로 올라가야 Consumer의 재시도와 DLT가 받을 수 있다. */
	@Test
	void doesNotHideAFailureFromTheIncidentDetector() {
		givenMetricOutcome(MetricRecordOutcome.APPLIED);
		willThrow(new IllegalStateException("판정 계층의 결함"))
			.given(this.incidentDetector)
			.detect(any(), any());

		assertThatIllegalStateException().isThrownBy(() -> this.processingService.process(event()));
	}

	/**
	 * 결과별 Counter는 처리가 실제로 어떻게 끝났는지를 그대로 따라가야 한다. 세는 쪽이 자기만의
	 * 분류를 만들면 대시보드의 숫자가 Consumer가 한 일과 다른 것을 말하게 된다.
	 */
	@Test
	void countsEachProcessedEventUnderTheOutcomeTheRecorderReported() {
		givenMetricOutcome(MetricRecordOutcome.APPLIED);
		this.processingService.process(event());
		givenMetricOutcome(MetricRecordOutcome.DUPLICATE);
		this.processingService.process(event());
		givenMetricOutcome(MetricRecordOutcome.OUT_OF_WINDOW);
		this.processingService.process(event());

		assertThat(processedCount("applied")).isEqualTo(1.0);
		assertThat(processedCount("duplicate")).isEqualTo(1.0);
		assertThat(processedCount("out_of_window")).isEqualTo(1.0);
		assertThat(failedCount()).isZero();
	}

	/**
	 * 실패한 Event를 처리된 것으로도 세면, 재시도 끝에 성공한 Event가 두 번 처리된 것처럼 보인다.
	 * 세는 일이 예외를 삼키지 않는다는 것도 함께 고정한다.
	 */
	@Test
	void countsAFailureWithoutCountingItAsProcessed() {
		givenMetricOutcome(MetricRecordOutcome.APPLIED);
		willThrow(new IllegalStateException("판정 계층의 결함"))
			.given(this.incidentDetector)
			.detect(any(), any());

		assertThatIllegalStateException().isThrownBy(() -> this.processingService.process(event()));

		assertThat(failedCount()).isEqualTo(1.0);
		assertThat(processedCount("applied")).isZero();
	}

	private double processedCount(String result) {
		return this.meterRegistry.get(TelemetryMetrics.PROCESSED_COUNTER)
			.tag(TelemetryMetrics.RESULT_TAG, result)
			.counter()
			.count();
	}

	private double failedCount() {
		return this.meterRegistry.get(TelemetryMetrics.FAILED_COUNTER).counter().count();
	}

	private void givenMetricOutcome(MetricRecordOutcome outcome) {
		given(this.metricRecorder.record(any())).willReturn(outcome);
	}

	/** 계열을 이루는 값 외에는 이 테스트의 관심사가 아니므로 고정한다. */
	private static TelemetryEvent event() {
		return new TelemetryEvent("3f1c8a27-6b90-4d1e-9c44-72b0d5e19a2b", 1, Instant.now(), SERVICE,
				"demo-shop-01:8080", "a7d2f0c1-3e55-4a8b-b0d6-1f9c2ab84e10", METHOD, ENDPOINT, 200, true, 12L, null,
				null);
	}

}

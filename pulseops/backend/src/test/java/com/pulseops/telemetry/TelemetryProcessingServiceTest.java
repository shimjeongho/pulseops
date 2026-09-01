package com.pulseops.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import com.pulseops.incident.IncidentDetector;
import com.pulseops.metric.MetricRecorder;
import com.pulseops.metric.MetricSeries;
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

	private final TelemetryProcessingService processingService = new TelemetryProcessingService(this.metricRecorder,
			this.incidentDetector);

	/** 판정 대상 계열은 Event가 가리키는 그 계열이어야 한다. 여기서 어긋나면 다른 API의 상태가 움직인다. */
	@Test
	void detectsOnTheSeriesOfAnEventThatChangedTheMetrics() {
		TelemetryEvent event = event();
		givenMetricApplied(true);

		this.processingService.process(event);

		ArgumentCaptor<MetricSeries> detected = ArgumentCaptor.forClass(MetricSeries.class);
		then(this.incidentDetector).should().detect(detected.capture(), any());
		assertThat(detected.getValue()).isEqualTo(new MetricSeries(SERVICE, METHOD, ENDPOINT));
	}

	/**
	 * 반영되지 않은 Event로 판정을 부르면 직전과 똑같은 창을 다시 읽고 다시 잠근다. 중복 전달, 집계
	 * 창 밖, Redis 쓰기 실패는 모두 "Metric이 달라지지 않았다"는 같은 결론이라 같은 자리에서 멈춘다.
	 */
	@Test
	void doesNotDetectWhenTheEventChangedNothing() {
		givenMetricApplied(false);

		this.processingService.process(event());

		then(this.incidentDetector).shouldHaveNoInteractions();
	}

	/** 판정 기준 시각은 집계 반영이 끝난 뒤에 정해진다. 반영 여부를 모르는 채로 창을 정할 이유가 없다. */
	@Test
	void picksTheEvaluationInstantAfterTheMetricIsApplied() {
		givenMetricApplied(true);
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

	/** 판정 쪽도 마찬가지다. 저장소 장애는 이미 그쪽이 흡수했으므로 여기까지 오는 것은 결함뿐이다. */
	@Test
	void doesNotHideAFailureFromTheIncidentDetector() {
		givenMetricApplied(true);
		willThrow(new IllegalStateException("판정 계층의 결함"))
			.given(this.incidentDetector)
			.detect(any(), any());

		assertThatIllegalStateException().isThrownBy(() -> this.processingService.process(event()));
	}

	private void givenMetricApplied(boolean applied) {
		given(this.metricRecorder.record(any())).willReturn(applied);
	}

	/** 계열을 이루는 값 외에는 이 테스트의 관심사가 아니므로 고정한다. */
	private static TelemetryEvent event() {
		return new TelemetryEvent("3f1c8a27-6b90-4d1e-9c44-72b0d5e19a2b", 1, Instant.now(), SERVICE,
				"demo-shop-01:8080", "a7d2f0c1-3e55-4a8b-b0d6-1f9c2ab84e10", METHOD, ENDPOINT, 200, true, 12L, null,
				null);
	}

}

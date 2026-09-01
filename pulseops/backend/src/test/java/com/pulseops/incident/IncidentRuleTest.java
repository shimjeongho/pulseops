package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseops.metric.MetricSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 창 하나가 어떤 판정을 받는지 기준선 위아래에서 고정한다.
 * <p>
 * 경계값을 직접 쓰는 이유는 기준선이 이상/정상을 가르는 계약 그 자체이기 때문이다. 10.00%가 이상이고
 * 9.99%가 아니라는 것이 조용히 뒤집히면 같은 장애가 어떤 배포에서는 Incident가 되고 어떤 배포에서는
 * 되지 않는다.
 * <p>
 * 여기서 검증하지 않는 것도 분명히 해 둔다. 이상이 몇 번 연속되어야 Incident가 열리는지는 이 Rule의
 * 책임이 아니므로 이 테스트에도 없다.
 */
class IncidentRuleTest {

	private static final Instant WINDOW_START = Instant.parse("2026-08-31T12:34:00Z");

	private static final Instant WINDOW_END = Instant.parse("2026-08-31T12:35:00Z");

	// --- ERROR_RATE ---------------------------------------------------------

	/**
	 * 요청이 20건 미만이면 오류율이 얼마든 판정하지 않는다. 요청이 아예 없는 창의 오류율은 0.0이지만
	 * 그것은 오류가 없었다는 뜻이 아니라 잴 것이 없었다는 뜻이다.
	 */
	@ParameterizedTest
	@CsvSource({
			"0,      0.00, INCONCLUSIVE, ",
			"19,    50.00, INCONCLUSIVE, ",
			"20,     0.00, HEALTHY,      ",
			"20,     2.99, HEALTHY,      ",
			"20,     3.00, NEUTRAL,      ",
			"20,     9.99, NEUTRAL,      ",
			"20,    10.00, ABNORMAL,     WARNING",
			"20,    24.99, ABNORMAL,     WARNING",
			"20,    25.00, ABNORMAL,     CRITICAL",
			"20,   100.00, ABNORMAL,     CRITICAL" })
	void classifiesAWindowByItsErrorRate(long requestCount, double errorRate, WindowVerdict.Outcome expectedOutcome,
			IncidentSeverity expectedSeverity) {
		WindowVerdict verdict = IncidentRule.evaluate(IncidentType.ERROR_RATE, snapshot(requestCount, errorRate, 100L));

		assertThat(verdict.outcome()).isEqualTo(expectedOutcome);
		assertThat(verdict.severity()).isEqualTo(expectedSeverity);
	}

	// --- LATENCY ------------------------------------------------------------

	/** 요청이 부족한 창과 응답 시간을 재지 못한 창은 모두 판정 대상이 아니다. */
	@ParameterizedTest
	@CsvSource({
			"0,        , INCONCLUSIVE, ",
			"19,    100, INCONCLUSIVE, ",
			"20,       , INCONCLUSIVE, ",
			"20,      0, HEALTHY,      ",
			"20,    499, HEALTHY,      ",
			"20,    500, NEUTRAL,      ",
			"20,   1499, NEUTRAL,      ",
			"20,   1500, ABNORMAL,     WARNING",
			"20,   2999, ABNORMAL,     WARNING",
			"20,   3000, ABNORMAL,     CRITICAL",
			"20,  60000, ABNORMAL,     CRITICAL" })
	void classifiesAWindowByItsP95Latency(long requestCount, Long p95LatencyMs, WindowVerdict.Outcome expectedOutcome,
			IncidentSeverity expectedSeverity) {
		WindowVerdict verdict = IncidentRule.evaluate(IncidentType.LATENCY, snapshot(requestCount, 0.00, p95LatencyMs));

		assertThat(verdict.outcome()).isEqualTo(expectedOutcome);
		assertThat(verdict.severity()).isEqualTo(expectedSeverity);
	}

	// --- 최소 트래픽 --------------------------------------------------------

	/** 같은 오류율이라도 요청이 20건이 되는 순간부터 판정한다. 20건은 판정 가능한 쪽이다. */
	@Test
	void startsDecidingExactlyAtTheMinimumRequestCount() {
		WindowVerdict belowMinimum = IncidentRule.evaluate(IncidentType.ERROR_RATE, snapshot(19, 50.00, 100L));
		WindowVerdict atMinimum = IncidentRule.evaluate(IncidentType.ERROR_RATE, snapshot(20, 50.00, 100L));

		assertThat(belowMinimum.outcome()).isEqualTo(WindowVerdict.Outcome.INCONCLUSIVE);
		assertThat(atMinimum.outcome()).isEqualTo(WindowVerdict.Outcome.ABNORMAL);
	}

	// --- 두 종류의 독립성 ---------------------------------------------------

	/** 오류 없이 느리기만 한 창이다. 응답 시간이 나빠도 오류율 판정은 그대로 정상이어야 한다. */
	@Test
	void judgesASlowButErrorFreeWindowAsHealthyOnErrorRateAndCriticalOnLatency() {
		MetricSnapshot slowWindow = snapshot(100, 0.00, 3500L);

		assertThat(IncidentRule.evaluate(IncidentType.ERROR_RATE, slowWindow).outcome())
			.isEqualTo(WindowVerdict.Outcome.HEALTHY);
		assertThat(IncidentRule.evaluate(IncidentType.LATENCY, slowWindow).outcome())
			.isEqualTo(WindowVerdict.Outcome.ABNORMAL);
	}

	/** 빠르게 실패하는 창이다. 실패가 빨리 돌아온다고 해서 오류율 이상이 가려지면 안 된다. */
	@Test
	void judgesAFastButFailingWindowAsCriticalOnErrorRateAndHealthyOnLatency() {
		MetricSnapshot failingWindow = snapshot(100, 30.00, 100L);

		assertThat(IncidentRule.evaluate(IncidentType.ERROR_RATE, failingWindow).severity())
			.isEqualTo(IncidentSeverity.CRITICAL);
		assertThat(IncidentRule.evaluate(IncidentType.LATENCY, failingWindow).outcome())
			.isEqualTo(WindowVerdict.Outcome.HEALTHY);
	}

	/** P95가 없는 창도 오류율은 판정할 수 있다. 한 종류의 결측이 다른 종류를 가리지 않는다. */
	@Test
	void judgesTheErrorRateEvenWithoutAP95() {
		MetricSnapshot withoutSamples = snapshot(100, 30.00, null);

		assertThat(IncidentRule.evaluate(IncidentType.ERROR_RATE, withoutSamples).outcome())
			.isEqualTo(WindowVerdict.Outcome.ABNORMAL);
	}

	/** 오류율은 창의 값을 그대로 쓴다. 오류 수는 그 값과 어긋나지 않도록 요청 수에서 되계산한다. */
	private static MetricSnapshot snapshot(long requestCount, double errorRate, Long p95LatencyMs) {
		long errorCount = Math.round(requestCount * errorRate / 100.0);
		return new MetricSnapshot("demo-shop", "GET", "/api/orders/{orderId}", WINDOW_START, WINDOW_END, requestCount,
				errorCount, errorRate, p95LatencyMs);
	}

}

package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.pulseops.metric.MetricSeries;
import com.pulseops.metric.MetricWindow;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 창 판정이 연속으로 몇 번 나왔는지를 어떤 규칙으로 세는지 DB 없이 고정한다.
 * <p>
 * 여기서 지키는 계약은 넷이다. 첫 창이 반드시 반영된다는 것, 연속은 정확히 한 Bucket 간격일 때만
 * 이어진다는 것, 회복은 HEALTHY만 잇는다는 것, 그리고 이미 본 창은 거절된다는 것이다. 이 중 하나만
 * 어긋나도 Incident가 열려야 할 때 열리지 않거나 아직 아픈 서비스의 Incident가 닫힌다.
 * <p>
 * 창의 끝은 테스트가 정한다. Entity가 시계를 읽지 않으므로 어떤 구간이든 그대로 넣어 볼 수 있다.
 */
class DetectionStateTest {

	private static final MetricSeries SERIES = new MetricSeries("demo-shop", "GET", "/api/orders/{orderId}");

	/** 이 상태로 처음 평가할 창의 끝이며 Bucket 경계에 맞춘 값이다. */
	private static final Instant FIRST_WINDOW_END = Instant.parse("2026-08-31T12:00:30Z");

	// --- 초기 상태 ----------------------------------------------------------

	@Test
	void initializesWithTheSeriesIdentityAndTypeAndNoCountedWindows() {
		DetectionState state = initialize();

		assertThat(state.getService()).isEqualTo("demo-shop");
		assertThat(state.getMethod()).isEqualTo("GET");
		assertThat(state.getEndpoint()).isEqualTo("/api/orders/{orderId}");
		assertThat(state.getType()).isEqualTo(IncidentType.ERROR_RATE);
		assertThat(state.getAbnormalConsecutive()).isZero();
		assertThat(state.getRecoveryConsecutive()).isZero();
	}

	/**
	 * cursor를 첫 창과 같게 두면 그 창이 이미 본 창이 되어 통째로 사라진다. 한 Bucket 앞에 두어야
	 * 첫 창이 다음 연속 창이 된다.
	 */
	@Test
	void initializesTheCursorOneBucketBeforeTheWindowItWillEvaluateFirst() {
		DetectionState state = initialize();

		assertThat(state.getLastEvaluatedWindowEnd()).isEqualTo(Instant.parse("2026-08-31T12:00:20Z"))
			.isEqualTo(FIRST_WINDOW_END.minusSeconds(MetricWindow.BUCKET_SECONDS));
		assertThat(state.getUpdatedAt()).isEqualTo(state.getLastEvaluatedWindowEnd());
	}

	/** 새로 만든 상태가 첫 창을 세지 못하면 이상이 처음 나타난 창이 언제나 한 번 무시된다. */
	@ParameterizedTest
	@CsvSource({ "ABNORMAL, 1, 0", "HEALTHY, 0, 1", "NEUTRAL, 0, 0", "INCONCLUSIVE, 0, 0" })
	void countsTheVeryFirstWindow(WindowVerdict.Outcome outcome, int expectedAbnormal, int expectedRecovery) {
		DetectionState state = initialize();

		state.apply(FIRST_WINDOW_END, verdictOf(outcome));

		assertThat(state.getLastEvaluatedWindowEnd()).isEqualTo(FIRST_WINDOW_END);
		assertThat(state.getAbnormalConsecutive()).isEqualTo(expectedAbnormal);
		assertThat(state.getRecoveryConsecutive()).isEqualTo(expectedRecovery);
	}

	// --- ABNORMAL -----------------------------------------------------------

	/** 2회가 Incident를 여는 기준이므로 그 위로는 세지 않는다. 상한이 없으면 값만 끝없이 커진다. */
	@Test
	void countsConsecutiveAbnormalWindowsUpToTwo() {
		DetectionState state = initialize();

		state.apply(windowEnd(0), WindowVerdict.abnormal(IncidentSeverity.WARNING));
		assertThat(state.getAbnormalConsecutive()).isEqualTo(1);

		state.apply(windowEnd(1), WindowVerdict.abnormal(IncidentSeverity.WARNING));
		assertThat(state.getAbnormalConsecutive()).isEqualTo(2);

		state.apply(windowEnd(2), WindowVerdict.abnormal(IncidentSeverity.WARNING));
		assertThat(state.getAbnormalConsecutive()).isEqualTo(2);

		state.apply(windowEnd(3), WindowVerdict.abnormal(IncidentSeverity.WARNING));
		assertThat(state.getAbnormalConsecutive()).isEqualTo(2);
	}

	/** 심각도가 달라도 이상이었다는 사실은 같으므로 하나의 연속으로 센다. */
	@Test
	void countsWarningAndCriticalAsOneAbnormalSequence() {
		DetectionState state = initialize();

		state.apply(windowEnd(0), WindowVerdict.abnormal(IncidentSeverity.WARNING));
		state.apply(windowEnd(1), WindowVerdict.abnormal(IncidentSeverity.CRITICAL));

		assertThat(state.getAbnormalConsecutive()).isEqualTo(2);
	}

	@Test
	void clearsTheRecoveryCountWhenAWindowIsAbnormal() {
		DetectionState state = initialize();
		state.apply(windowEnd(0), WindowVerdict.healthy());
		state.apply(windowEnd(1), WindowVerdict.healthy());

		state.apply(windowEnd(2), WindowVerdict.abnormal(IncidentSeverity.WARNING));

		assertThat(state.getRecoveryConsecutive()).isZero();
		assertThat(state.getAbnormalConsecutive()).isEqualTo(1);
	}

	// --- HEALTHY ------------------------------------------------------------

	@Test
	void countsConsecutiveHealthyWindowsUpToThree() {
		DetectionState state = initialize();

		state.apply(windowEnd(0), WindowVerdict.healthy());
		assertThat(state.getRecoveryConsecutive()).isEqualTo(1);

		state.apply(windowEnd(1), WindowVerdict.healthy());
		assertThat(state.getRecoveryConsecutive()).isEqualTo(2);

		state.apply(windowEnd(2), WindowVerdict.healthy());
		assertThat(state.getRecoveryConsecutive()).isEqualTo(3);

		state.apply(windowEnd(3), WindowVerdict.healthy());
		assertThat(state.getRecoveryConsecutive()).isEqualTo(3);
	}

	@Test
	void clearsTheAbnormalCountWhenAWindowIsHealthy() {
		DetectionState state = initialize();
		state.apply(windowEnd(0), WindowVerdict.abnormal(IncidentSeverity.CRITICAL));

		state.apply(windowEnd(1), WindowVerdict.healthy());

		assertThat(state.getAbnormalConsecutive()).isZero();
		assertThat(state.getRecoveryConsecutive()).isEqualTo(1);
	}

	// --- NEUTRAL / INCONCLUSIVE ---------------------------------------------

	/**
	 * 기준선 사이의 창도, 트래픽이 없거나 P95를 구하지 못한 창도 좋아졌다는 근거가 아니다. 이것으로
	 * 회복이 쌓이면 아직 아픈 서비스의 Incident가 닫힌다.
	 */
	@ParameterizedTest
	@EnumSource(value = WindowVerdict.Outcome.class, names = { "NEUTRAL", "INCONCLUSIVE" })
	void clearsBothCountsWithoutCountingAsRecovery(WindowVerdict.Outcome outcome) {
		DetectionState afterAbnormal = initialize();
		afterAbnormal.apply(windowEnd(0), WindowVerdict.abnormal(IncidentSeverity.WARNING));
		DetectionState afterHealthy = initialize();
		afterHealthy.apply(windowEnd(0), WindowVerdict.healthy());

		afterAbnormal.apply(windowEnd(1), verdictOf(outcome));
		afterHealthy.apply(windowEnd(1), verdictOf(outcome));

		assertThat(afterAbnormal.getAbnormalConsecutive()).isZero();
		assertThat(afterAbnormal.getRecoveryConsecutive()).isZero();
		assertThat(afterHealthy.getAbnormalConsecutive()).isZero();
		assertThat(afterHealthy.getRecoveryConsecutive()).isZero();
	}

	// --- 창 순서 ------------------------------------------------------------

	@Test
	void movesTheCursorAndTheUpdateTimeToTheAppliedWindow() {
		DetectionState state = initialize();

		state.apply(windowEnd(0), WindowVerdict.neutral());

		assertThat(state.getLastEvaluatedWindowEnd()).isEqualTo(windowEnd(0));
		assertThat(state.getUpdatedAt()).isEqualTo(windowEnd(0));
	}

	@Test
	void rejectsTheWindowItHasAlreadyEvaluated() {
		DetectionState state = initialize();
		state.apply(windowEnd(0), WindowVerdict.abnormal(IncidentSeverity.WARNING));

		assertThatIllegalStateException()
			.isThrownBy(() -> state.apply(windowEnd(0), WindowVerdict.abnormal(IncidentSeverity.WARNING)));

		assertThat(state.getAbnormalConsecutive()).isEqualTo(1);
	}

	@Test
	void rejectsAWindowOlderThanTheOneItHasEvaluated() {
		DetectionState state = initialize();
		state.apply(windowEnd(1), WindowVerdict.abnormal(IncidentSeverity.WARNING));

		assertThatIllegalStateException()
			.isThrownBy(() -> state.apply(windowEnd(0), WindowVerdict.abnormal(IncidentSeverity.WARNING)));

		assertThat(state.getLastEvaluatedWindowEnd()).isEqualTo(windowEnd(1));
	}

	// --- 끊긴 구간 ----------------------------------------------------------

	/**
	 * 건너뛴 구간이 어땠는지 모르므로 그 앞의 연속은 근거가 되지 못한다. 다만 지금 창은 실제로 관측한
	 * 창이라 버리지 않고 새 연속의 첫 창으로 센다. 여기서 0이 되면 이상이 이어지는 동안에도 Incident가
	 * 열리지 않는다.
	 */
	@Test
	void startsANewAbnormalSequenceWhenAWindowIsMissing() {
		DetectionState state = initialize();
		state.apply(windowEnd(0), WindowVerdict.abnormal(IncidentSeverity.WARNING));

		state.apply(windowEnd(2), WindowVerdict.abnormal(IncidentSeverity.WARNING));

		assertThat(state.getAbnormalConsecutive()).isEqualTo(1);
		assertThat(state.getRecoveryConsecutive()).isZero();
		assertThat(state.getLastEvaluatedWindowEnd()).isEqualTo(windowEnd(2));
	}

	@Test
	void startsANewRecoverySequenceWhenAWindowIsMissing() {
		DetectionState state = initialize();
		state.apply(windowEnd(0), WindowVerdict.healthy());
		state.apply(windowEnd(1), WindowVerdict.healthy());

		state.apply(windowEnd(3), WindowVerdict.healthy());

		assertThat(state.getAbnormalConsecutive()).isZero();
		assertThat(state.getRecoveryConsecutive()).isEqualTo(1);
		assertThat(state.getLastEvaluatedWindowEnd()).isEqualTo(windowEnd(3));
	}

	/** Bucket 경계에서 어긋난 창은 앞 창의 바로 다음이 아니므로 연속이 아니다. */
	@Test
	void treatsAWindowThatIsNotExactlyOneBucketAheadAsMissing() {
		DetectionState state = initialize();
		state.apply(windowEnd(0), WindowVerdict.abnormal(IncidentSeverity.WARNING));

		state.apply(windowEnd(0).plusSeconds(MetricWindow.BUCKET_SECONDS + 1),
				WindowVerdict.abnormal(IncidentSeverity.WARNING));

		assertThat(state.getAbnormalConsecutive()).isEqualTo(1);
	}

	private static DetectionState initialize() {
		return DetectionState.initialize(SERIES, IncidentType.ERROR_RATE, FIRST_WINDOW_END);
	}

	/** 연속된 창의 끝이다. 0번이 이 상태가 처음 평가할 창이다. */
	private static Instant windowEnd(int index) {
		return FIRST_WINDOW_END.plusSeconds((long) index * MetricWindow.BUCKET_SECONDS);
	}

	/** ABNORMAL의 심각도는 연속을 세는 데 쓰이지 않으므로 하나로 고정한다. */
	private static WindowVerdict verdictOf(WindowVerdict.Outcome outcome) {
		return switch (outcome) {
			case ABNORMAL -> WindowVerdict.abnormal(IncidentSeverity.WARNING);
			case HEALTHY -> WindowVerdict.healthy();
			case NEUTRAL -> WindowVerdict.neutral();
			case INCONCLUSIVE -> WindowVerdict.inconclusive();
		};
	}

}

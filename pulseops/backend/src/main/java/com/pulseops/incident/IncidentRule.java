package com.pulseops.incident;

import com.pulseops.metric.MetricSnapshot;

/**
 * 창 하나가 이상인지 정상인지를 판정한다.
 * <p>
 * Metric을 다시 계산하지 않는다. 오류율과 P95는 이미 {@link MetricSnapshot}이 정한 값이고, 여기서는
 * 그 값을 기준선과 비교하기만 한다. 같은 계산이 두 곳에 있으면 한쪽만 고쳐졌을 때 Incident의 근거로
 * 남는 숫자와 판정에 쓰인 숫자가 어긋난다.
 * <p>
 * 오류율의 오류는 Phase 3에서 정한 대로 5xx뿐이다. 4xx는 요청 수에만 들어간다. 잘못된 요청이 많은
 * 것은 서비스의 이상이 아니므로 그것으로 Incident를 열지 않는다.
 * <p>
 * 시각도 저장소도 알지 못하는 순수 계산이라 Spring Bean으로 두지 않고 {@code MetricWindow}처럼 정적
 * method만 갖는다. 상태가 없어 주입해서 바꿔 끼울 이유가 없고, 주입하려면 쓰이지 않는 생성자와
 * 인스턴스가 생긴다.
 * <p>
 * 이 판정은 창 하나에 대한 것이다. 이상이 몇 번 연속됐는지 세거나 Incident를 여는 일은 여기서 하지
 * 않는다.
 */
public final class IncidentRule {

	/**
	 * 이보다 요청이 적은 창은 판정하지 않는다. 요청 두 건 중 한 건이 실패해도 오류율은 50%가 되지만
	 * 그것으로 서비스가 아프다고 말할 수 없다.
	 */
	private static final long MIN_REQUEST_COUNT = 20;

	/** 오류율은 0~100의 백분율이다. */
	private static final double ERROR_RATE_CRITICAL_PERCENT = 25.00;

	private static final double ERROR_RATE_WARNING_PERCENT = 10.00;

	/**
	 * 이 아래여야 HEALTHY다. 이상 기준(10%)보다 낮게 잡아 그 사이를 완충 구간으로 남긴다. 두 기준이
	 * 같으면 값이 기준선에서 흔들릴 때마다 판정이 뒤집힌다. 이 상수는 회복 판정의 기준선일 뿐이고
	 * 회복을 몇 번 확인할지는 이 Class의 관심사가 아니다.
	 */
	private static final double ERROR_RATE_HEALTHY_PERCENT = 3.00;

	private static final long LATENCY_CRITICAL_MS = 3000;

	private static final long LATENCY_WARNING_MS = 1500;

	/** 이상 기준(1500ms)보다 낮은 회복 기준선이다. 이유는 {@link #ERROR_RATE_HEALTHY_PERCENT}와 같다. */
	private static final long LATENCY_HEALTHY_MS = 500;

	private IncidentRule() {
	}

	/**
	 * 종류를 인자로 받는다. 종류별 method를 따로 열면 부르는 쪽이 종류와 method를 짝지어야 하고, 그
	 * 짝짓기가 Detector에도 또 생긴다. 판정 결과는 종류별로 따로 기억되므로 부르는 쪽은 어차피
	 * {@link IncidentType}을 손에 들고 있다.
	 * <p>
	 * 기본 분기를 두지 않아 종류가 늘어나면 여기서 compile이 깨진다. 새 종류가 조용히 정상으로
	 * 판정되는 것보다 낫다.
	 */
	public static WindowVerdict evaluate(IncidentType type, MetricSnapshot snapshot) {
		return switch (type) {
			case ERROR_RATE -> evaluateErrorRate(snapshot);
			case LATENCY -> evaluateLatency(snapshot);
		};
	}

	private static WindowVerdict evaluateErrorRate(MetricSnapshot snapshot) {
		if (snapshot.requestCount() < MIN_REQUEST_COUNT) {
			return WindowVerdict.inconclusive();
		}
		double errorRate = snapshot.errorRate();
		if (errorRate >= ERROR_RATE_CRITICAL_PERCENT) {
			return WindowVerdict.abnormal(IncidentSeverity.CRITICAL);
		}
		if (errorRate >= ERROR_RATE_WARNING_PERCENT) {
			return WindowVerdict.abnormal(IncidentSeverity.WARNING);
		}
		if (errorRate < ERROR_RATE_HEALTHY_PERCENT) {
			return WindowVerdict.healthy();
		}
		return WindowVerdict.neutral();
	}

	/**
	 * 요청이 충분해도 P95가 없으면 판정하지 않는다. 표본이 없어 구하지 못한 값을 0으로 읽으면 응답
	 * 시간을 재지 못한 창이 가장 빠른 창이 된다.
	 */
	private static WindowVerdict evaluateLatency(MetricSnapshot snapshot) {
		if (snapshot.requestCount() < MIN_REQUEST_COUNT) {
			return WindowVerdict.inconclusive();
		}
		Long p95LatencyMs = snapshot.p95LatencyMs();
		if (p95LatencyMs == null) {
			return WindowVerdict.inconclusive();
		}
		if (p95LatencyMs >= LATENCY_CRITICAL_MS) {
			return WindowVerdict.abnormal(IncidentSeverity.CRITICAL);
		}
		if (p95LatencyMs >= LATENCY_WARNING_MS) {
			return WindowVerdict.abnormal(IncidentSeverity.WARNING);
		}
		if (p95LatencyMs < LATENCY_HEALTHY_MS) {
			return WindowVerdict.healthy();
		}
		return WindowVerdict.neutral();
	}

}

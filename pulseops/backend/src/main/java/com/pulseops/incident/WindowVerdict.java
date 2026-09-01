package com.pulseops.incident;

import java.util.Objects;

/**
 * 하나의 Metric 창을 한 종류의 이상 기준으로 판정한 결과이다.
 * <p>
 * 네 가지 결과를 구분한다. 판단할 근거가 부족한 INCONCLUSIVE, 기준을 넘은 ABNORMAL, 확실히 정상인
 * HEALTHY, 그 사이의 NEUTRAL이다. 정상 여부를 boolean 하나로 두지 않는 이유는 "트래픽이 없어서
 * 모른다"와 "정상이다"가 전혀 다른 사실이기 때문이다. 둘을 같은 값으로 뭉개면 요청이 끊긴 서비스가
 * 건강한 서비스로 보인다. NEUTRAL도 마찬가지로 HEALTHY가 아니다. 기준선 바로 아래에서 값이 오르내릴
 * 때 Incident가 열렸다 닫혔다 하는 것을 막는 완충 구간이며, 그 구간을 표현할 값이 없으면 완충 자체가
 * 불가능하다.
 * <p>
 * 심각도는 ABNORMAL일 때만 뜻이 있다. 정상인 창에 WARNING이 붙는 조합은 애초에 만들어질 수 없어야
 * 하므로 생성자를 열지 않고 factory로만 만든다. 그래서 {@link #severity()}가 null인지는 결과가
 * ABNORMAL인지와 언제나 같으며, 판정 결과를 읽는 쪽이 두 값을 따로 검사할 필요가 없다.
 * <p>
 * 없는 심각도는 {@code Optional}이 아니라 null로 둔다. 값이 없을 수 있는 다른 Metric 값들과 표현을
 * 맞춘 것이다.
 * <p>
 * 이 판정은 창 하나에 대한 것이고 그 자체로는 Incident를 열지도 닫지도 않는다. 연속으로 몇 번
 * 나왔는지를 세는 일은 이 Class의 책임이 아니다.
 */
public final class WindowVerdict {

	private static final WindowVerdict INCONCLUSIVE = new WindowVerdict(Outcome.INCONCLUSIVE, null);

	private static final WindowVerdict HEALTHY = new WindowVerdict(Outcome.HEALTHY, null);

	private static final WindowVerdict NEUTRAL = new WindowVerdict(Outcome.NEUTRAL, null);

	private final Outcome outcome;

	private final IncidentSeverity severity;

	private WindowVerdict(Outcome outcome, IncidentSeverity severity) {
		this.outcome = outcome;
		this.severity = severity;
	}

	/** 표본이 부족해 정상인지 아닌지 말할 수 없는 창이다. 정상이라는 뜻이 아니다. */
	public static WindowVerdict inconclusive() {
		return INCONCLUSIVE;
	}

	/** 기준을 넘은 창이다. 얼마나 넘었는지를 함께 남기므로 심각도가 반드시 있어야 한다. */
	public static WindowVerdict abnormal(IncidentSeverity severity) {
		Objects.requireNonNull(severity, "ABNORMAL 판정에는 심각도가 있어야 한다");
		return new WindowVerdict(Outcome.ABNORMAL, severity);
	}

	/** 회복 기준까지 내려온 창이다. */
	public static WindowVerdict healthy() {
		return HEALTHY;
	}

	/** 이상도 회복도 아닌 창이다. */
	public static WindowVerdict neutral() {
		return NEUTRAL;
	}

	public Outcome outcome() {
		return this.outcome;
	}

	/** ABNORMAL이 아닌 판정에서는 null이다. */
	public IncidentSeverity severity() {
		return this.severity;
	}

	public enum Outcome {

		INCONCLUSIVE,
		ABNORMAL,
		HEALTHY,
		NEUTRAL

	}

}

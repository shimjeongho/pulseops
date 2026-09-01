package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 심각도가 ABNORMAL에만 붙는다는 불변식을 공개 API 기준으로 고정한다.
 * <p>
 * 이 불변식이 깨지면 "정상인데 CRITICAL" 같은 결과가 만들어지고, 판정을 읽는 쪽이 결과와 심각도를
 * 매번 따로 확인해야 한다.
 */
class WindowVerdictTest {

	@ParameterizedTest
	@EnumSource(IncidentSeverity.class)
	void carriesTheGivenSeverityWhenAbnormal(IncidentSeverity severity) {
		WindowVerdict verdict = WindowVerdict.abnormal(severity);

		assertThat(verdict.outcome()).isEqualTo(WindowVerdict.Outcome.ABNORMAL);
		assertThat(verdict.severity()).isEqualTo(severity);
	}

	/** 심각도 없는 ABNORMAL은 만들 수 없다. 얼마나 나쁜지 모르면 Incident를 열 수도 없다. */
	@Test
	void rejectsAnAbnormalVerdictWithoutASeverity() {
		assertThatNullPointerException().isThrownBy(() -> WindowVerdict.abnormal(null));
	}

	@Test
	void hasNoSeverityWhenNotAbnormal() {
		assertThat(WindowVerdict.inconclusive().outcome()).isEqualTo(WindowVerdict.Outcome.INCONCLUSIVE);
		assertThat(WindowVerdict.healthy().outcome()).isEqualTo(WindowVerdict.Outcome.HEALTHY);
		assertThat(WindowVerdict.neutral().outcome()).isEqualTo(WindowVerdict.Outcome.NEUTRAL);
		assertThat(WindowVerdict.inconclusive().severity()).isNull();
		assertThat(WindowVerdict.healthy().severity()).isNull();
		assertThat(WindowVerdict.neutral().severity()).isNull();
	}

}

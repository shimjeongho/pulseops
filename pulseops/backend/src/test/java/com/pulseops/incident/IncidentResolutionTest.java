package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Incident가 열려 있던 시간을 초로 재는 방식을 고정한다. Daily Report의 평균과 최댓값이 전부 이
 * 값 위에 쌓이므로, 여기서 반올림 방향이 바뀌면 Report의 두 숫자가 함께 움직인다.
 */
class IncidentResolutionTest {

	private static final Instant OPENED_AT = Instant.parse("2026-09-03T10:00:00Z");

	@Test
	void measuresTheSecondsBetweenOpeningAndResolving() {
		IncidentResolution resolution = new IncidentResolution(OPENED_AT, Instant.parse("2026-09-03T10:02:30Z"));

		assertThat(resolution.durationSeconds()).isEqualTo(150);
	}

	/** 초 미만은 버린다. Incident의 해결 시간에서 밀리초는 읽는 사람의 판단을 바꾸지 않는다. */
	@Test
	void dropsAnythingBelowASecond() {
		IncidentResolution resolution = new IncidentResolution(OPENED_AT, Instant.parse("2026-09-03T10:00:09.999Z"));

		assertThat(resolution.durationSeconds()).isEqualTo(9);
	}

	@Test
	void measuresZeroWhenResolvedInTheSameSecond() {
		assertThat(new IncidentResolution(OPENED_AT, OPENED_AT).durationSeconds()).isZero();
	}

}

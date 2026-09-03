package com.pulseops.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/**
 * 집계 결과가 스스로 모순되지 않는지 확인한다.
 * <p>
 * 여기서 막는 것은 저장 이후에는 알아볼 수 없는 종류의 잘못이다. 해결된 Incident가 없는데 평균
 * 해결 시간이 0으로 남으면, 그 Report를 읽는 사람은 모든 장애가 즉시 해결된 하루로 읽는다.
 */
class DailyIncidentAggregateTest {

	@Test
	void keepsResolutionStatisticsAbsentWhenNothingWasResolved() {
		DailyIncidentAggregate aggregate = new DailyIncidentAggregate(3, 2, 1, 1, 0, 3, null, null);

		assertThat(aggregate.resolvedCount()).isZero();
		assertThat(aggregate.averageResolutionSeconds()).isNull();
		assertThat(aggregate.maxResolutionSeconds()).isNull();
	}

	/** 아무 일도 없던 하루도 정상적인 집계 결과이다. */
	@Test
	void acceptsADayWithoutAnyIncident() {
		DailyIncidentAggregate aggregate = new DailyIncidentAggregate(0, 0, 0, 0, 0, 0, null, null);

		assertThat(aggregate.openedCount()).isZero();
		assertThat(aggregate.unresolvedAtPeriodEndCount()).isZero();
	}

	@Test
	void rejectsResolutionStatisticsWithoutAnyResolvedIncident() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new DailyIncidentAggregate(1, 1, 0, 0, 0, 1, 30L, 30L));
	}

	@Test
	void rejectsAResolvedIncidentWithoutResolutionStatistics() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new DailyIncidentAggregate(1, 1, 0, 0, 1, 0, null, null));
	}

	/** 평균만 있고 최댓값이 없는 절반짜리 통계도 남기지 않는다. */
	@Test
	void rejectsHalfFilledResolutionStatistics() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new DailyIncidentAggregate(1, 1, 0, 0, 1, 0, 30L, null));
	}

	@Test
	void rejectsANegativeCount() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new DailyIncidentAggregate(-1, 0, 0, 0, 0, 0, null, null));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new DailyIncidentAggregate(0, 0, 0, -1, 0, 0, null, null));
	}

}

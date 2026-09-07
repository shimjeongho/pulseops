package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Incident 관측값이 무엇을 근거로 만들어지고 무엇을 감추지 않는지를 고정한다.
 * <p>
 * 여기서 보는 것은 탐지가 아니라 관측이다. 언제 Incident가 열리는지는 {@link IncidentRuleTest}와
 * {@link IncidentDetectorIntegrationTest}가 이미 보고 있으므로, 이 테스트는 이미 일어난 전이와 이미
 * 저장된 상태가 어떤 숫자로 나가는지만 확인한다.
 */
class IncidentMetricsTest {

	private final IncidentRepository incidentRepository = mock(IncidentRepository.class);

	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private final IncidentMetrics incidentMetrics = new IncidentMetrics(this.incidentRepository, this.meterRegistry);

	/** 열린 건수는 종류와 심각도로 나눠 센다. 두 값 모두 열릴 때 정해진 뒤 이 Counter에서 바뀌지 않는다. */
	@Test
	void countsOpenedIncidentsByTypeAndSeverity() {
		this.incidentMetrics.onIncidentLifecycle(opened(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));
		this.incidentMetrics.onIncidentLifecycle(opened(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));
		this.incidentMetrics.onIncidentLifecycle(opened(IncidentType.LATENCY, IncidentSeverity.WARNING));

		assertThat(openedCount(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL)).isEqualTo(2.0);
		assertThat(openedCount(IncidentType.LATENCY, IncidentSeverity.WARNING)).isEqualTo(1.0);
		assertThat(openedCount(IncidentType.LATENCY, IncidentSeverity.CRITICAL)).isZero();
	}

	/**
	 * 끝난 건수에는 심각도가 없다. 심각도는 열린 뒤 올라갈 수 있어, 심각도별로 열림과 끝남을 나란히
	 * 놓으면 열린 적 없는 것이 끝난 것처럼 보이는 조합이 생긴다.
	 */
	@Test
	void countsResolvedIncidentsByTypeWithoutSeverity() {
		this.incidentMetrics
			.onIncidentLifecycle(new IncidentLifecycleEvent(IncidentLifecycleTransition.RESOLVED,
					IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL));

		Meter.Id resolved = this.meterRegistry.get(IncidentMetrics.RESOLVED_COUNTER)
			.tag(IncidentMetrics.TYPE_TAG, IncidentType.ERROR_RATE.name())
			.counter()
			.getId();
		assertThat(resolved.getTags()).extracting(Tag::getKey).containsExactly(IncidentMetrics.TYPE_TAG);
		assertThat(this.meterRegistry.get(IncidentMetrics.RESOLVED_COUNTER)
			.tag(IncidentMetrics.TYPE_TAG, IncidentType.ERROR_RATE.name())
			.counter()
			.count()).isEqualTo(1.0);
	}

	/** 지금 몇 건이 처리 중인가의 근거는 언제나 DB이다. 열림과 끝남을 더하고 빼서 만들지 않는다. */
	@Test
	void readsActiveIncidentCountsFromTheDatabase() {
		given(this.incidentRepository.countActiveByTypeAndSeverity())
			.willReturn(List.of(new ActiveIncidentCount(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL, 3L)));

		assertThat(activeCount(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL)).isEqualTo(3.0);
	}

	/** 조회 결과에 없는 조합은 0이다. 그 조합의 Incident가 하나도 없다는 뜻이며 관측 실패가 아니다. */
	@Test
	void reportsZeroForACombinationWithNoActiveIncident() {
		given(this.incidentRepository.countActiveByTypeAndSeverity()).willReturn(List.of());

		assertThat(activeCount(IncidentType.LATENCY, IncidentSeverity.WARNING)).isZero();
	}

	/**
	 * DB를 읽지 못한 것을 0으로 답하면 DB 장애가 대시보드에서 "처리 중인 장애 없음"으로 보인다.
	 * 값이 없는 구간으로 남겨야 읽지 못하고 있다는 사실이 드러난다.
	 */
	@Test
	void leavesActiveGaugesEmptyWhenTheDatabaseCannotBeRead() {
		given(this.incidentRepository.countActiveByTypeAndSeverity())
			.willThrow(new IllegalStateException("DB에 닿지 못했다"));

		assertThat(activeCount(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL)).isNaN();
	}

	/**
	 * 한 번의 scrape가 하나의 장면을 뜻해야 한다. Gauge마다 DB를 새로 읽으면 네 숫자가 서로 다른
	 * 순간의 것이 되어 그 합이 어느 시점의 처리 중 건수와도 같지 않을 수 있다.
	 */
	@Test
	void readsTheDatabaseOnceForOneScrapeOfEveryCombination() {
		given(this.incidentRepository.countActiveByTypeAndSeverity()).willReturn(List.of());

		for (IncidentType type : IncidentType.values()) {
			for (IncidentSeverity severity : IncidentSeverity.values()) {
				activeCount(type, severity);
			}
		}

		then(this.incidentRepository).should().countActiveByTypeAndSeverity();
	}

	/**
	 * Incident ID와 계열은 Tag가 아니다. 장애가 하나 열릴 때마다 새 시계열이 생기면 그 시계열은
	 * Incident가 끝난 뒤에도 남아 계속 쌓인다.
	 */
	@Test
	void tagsIncidentMetricsOnlyWithBoundedValues() {
		List<Meter> incidentMeters = this.meterRegistry.getMeters()
			.stream()
			.filter((meter) -> meter.getId().getName().startsWith("pulseops.incidents."))
			.toList();

		assertThat(incidentMeters).isNotEmpty();
		assertThat(incidentMeters).allSatisfy((meter) -> assertThat(meter.getId().getTags())
			.extracting(Tag::getKey)
			.isSubsetOf(IncidentMetrics.TYPE_TAG, IncidentMetrics.SEVERITY_TAG));
	}

	private static IncidentLifecycleEvent opened(IncidentType type, IncidentSeverity severity) {
		return new IncidentLifecycleEvent(IncidentLifecycleTransition.OPENED, type, severity);
	}

	private double openedCount(IncidentType type, IncidentSeverity severity) {
		return this.meterRegistry.get(IncidentMetrics.OPENED_COUNTER)
			.tag(IncidentMetrics.TYPE_TAG, type.name())
			.tag(IncidentMetrics.SEVERITY_TAG, severity.name())
			.counter()
			.count();
	}

	private double activeCount(IncidentType type, IncidentSeverity severity) {
		return this.meterRegistry.get(IncidentMetrics.ACTIVE_GAUGE)
			.tag(IncidentMetrics.TYPE_TAG, type.name())
			.tag(IncidentMetrics.SEVERITY_TAG, severity.name())
			.gauge()
			.value();
	}

}

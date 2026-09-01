package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.pulseops.metric.MetricSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Incident가 어떤 전이를 허용하고 어떤 전이를 막는지 DB 없이 고정한다.
 * <p>
 * 여기서 지키는 계약은 세 가지다. 한 번 열린 근거는 바뀌지 않는다는 것, 심각도는 올라가기만 한다는
 * 것, 그리고 RESOLVED에서는 어떤 전이도 나갈 수 없다는 것이다. 이 셋이 깨지면 하나의 장애가 언제
 * 시작해서 언제 끝났고 얼마나 나빴는지를 기록에서 읽을 수 없게 된다.
 * <p>
 * 시각은 테스트가 정한다. Incident가 시계를 읽지 않으므로 전이 시각을 그대로 검증할 수 있다.
 */
class IncidentTest {

	private static final Instant WINDOW_START = Instant.parse("2026-08-31T12:34:00Z");

	private static final Instant OPENED_AT = Instant.parse("2026-08-31T12:35:00Z");

	private static final Instant ACKNOWLEDGED_AT = Instant.parse("2026-08-31T12:36:30Z");

	private static final Instant ESCALATED_AT = Instant.parse("2026-08-31T12:37:10Z");

	private static final Instant RESOLVED_AT = Instant.parse("2026-08-31T12:40:00Z");

	/** 창의 끝이 곧 Incident가 열린 시각이다. Detection이 닫힌 창을 근거로 판단하기 때문이다. */
	private static final MetricSnapshot TRIGGER = new MetricSnapshot("demo-shop", "GET", "/api/orders/{orderId}",
			WINDOW_START, OPENED_AT, 120, 30, 25.0, 240L);

	@Test
	void opensWithTheGivenTypeAndSeverityAndNothingResolvedYet() {
		Incident incident = openWarning();

		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
		assertThat(incident.getType()).isEqualTo(IncidentType.ERROR_RATE);
		assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.WARNING);
		assertThat(incident.getAcknowledgedAt()).isNull();
		assertThat(incident.getResolvedAt()).isNull();
	}

	@Test
	void copiesTheObservedWindowAsEvidence() {
		Incident incident = openWarning();

		assertThat(incident.getService()).isEqualTo("demo-shop");
		assertThat(incident.getMethod()).isEqualTo("GET");
		assertThat(incident.getEndpoint()).isEqualTo("/api/orders/{orderId}");
		assertThat(incident.getTriggerWindowStart()).isEqualTo(WINDOW_START);
		assertThat(incident.getTriggerWindowEnd()).isEqualTo(OPENED_AT);
		assertThat(incident.getTriggerRequestCount()).isEqualTo(120);
		assertThat(incident.getTriggerErrorCount()).isEqualTo(30);
		assertThat(incident.getTriggerErrorRate()).isEqualTo(new BigDecimal("25.00"));
		assertThat(incident.getTriggerP95LatencyMs()).isEqualTo(240L);
	}

	@Test
	void startsWithUpdatedAtEqualToOpenedAt() {
		Incident incident = openWarning();

		assertThat(incident.getOpenedAt()).isEqualTo(OPENED_AT);
		assertThat(incident.getUpdatedAt()).isEqualTo(OPENED_AT);
	}

	/**
	 * 저장되는 오류율은 소수 둘째 자리까지다. 나눗셈 결과를 그대로 두면 자리수가 창마다 달라져,
	 * 같은 뜻의 값이 기록마다 다른 모양으로 남는다.
	 */
	@Test
	void roundsTheErrorRateToTheStoredScale() {
		MetricSnapshot uneven = new MetricSnapshot("demo-shop", "GET", "/api/orders/{orderId}", WINDOW_START,
				OPENED_AT, 120, 15, 12.3456, 240L);

		Incident incident = Incident.open(IncidentType.ERROR_RATE, IncidentSeverity.WARNING, uneven, OPENED_AT);

		assertThat(incident.getTriggerErrorRate()).isEqualTo(new BigDecimal("12.35"));
	}

	/** 표본이 없어 P95를 구하지 못한 창도 Incident의 근거가 될 수 있다. */
	@Test
	void keepsAMissingP95AsMissing() {
		MetricSnapshot withoutSamples = new MetricSnapshot("demo-shop", "GET", "/api/orders/{orderId}", WINDOW_START,
				OPENED_AT, 120, 30, 25.0, null);

		Incident incident = Incident.open(IncidentType.LATENCY, IncidentSeverity.WARNING, withoutSamples, OPENED_AT);

		assertThat(incident.getTriggerP95LatencyMs()).isNull();
	}

	@Test
	void acknowledgesAnOpenIncident() {
		Incident incident = openWarning();

		incident.acknowledge(ACKNOWLEDGED_AT);

		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		assertThat(incident.getAcknowledgedAt()).isEqualTo(ACKNOWLEDGED_AT);
		assertThat(incident.getUpdatedAt()).isEqualTo(ACKNOWLEDGED_AT);
		assertThat(incident.getResolvedAt()).isNull();
	}

	@Test
	void rejectsAcknowledgingAnAlreadyAcknowledgedIncident() {
		Incident incident = openWarning();
		incident.acknowledge(ACKNOWLEDGED_AT);

		assertThatIllegalStateException().isThrownBy(() -> incident.acknowledge(ESCALATED_AT));

		assertThat(incident.getAcknowledgedAt()).isEqualTo(ACKNOWLEDGED_AT);
	}

	@Test
	void rejectsAcknowledgingAResolvedIncident() {
		Incident incident = openWarning();
		incident.resolve(RESOLVED_AT);

		assertThatIllegalStateException().isThrownBy(() -> incident.acknowledge(ACKNOWLEDGED_AT));
	}

	@Test
	void resolvesAnOpenIncident() {
		Incident incident = openWarning();

		incident.resolve(RESOLVED_AT);

		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
		assertThat(incident.getResolvedAt()).isEqualTo(RESOLVED_AT);
		assertThat(incident.getUpdatedAt()).isEqualTo(RESOLVED_AT);
		assertThat(incident.getAcknowledgedAt()).isNull();
	}

	/** 확인을 거쳤다는 사실은 해결된 뒤에도 남아야 한다. */
	@Test
	void resolvesAnAcknowledgedIncidentWithoutErasingWhoLookedAtIt() {
		Incident incident = openWarning();
		incident.acknowledge(ACKNOWLEDGED_AT);

		incident.resolve(RESOLVED_AT);

		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
		assertThat(incident.getAcknowledgedAt()).isEqualTo(ACKNOWLEDGED_AT);
		assertThat(incident.getResolvedAt()).isEqualTo(RESOLVED_AT);
	}

	@Test
	void rejectsResolvingAResolvedIncident() {
		Incident incident = openWarning();
		incident.resolve(RESOLVED_AT);

		assertThatIllegalStateException().isThrownBy(() -> incident.resolve(ESCALATED_AT));

		assertThat(incident.getResolvedAt()).isEqualTo(RESOLVED_AT);
	}

	@Test
	void escalatesFromWarningToCriticalWithoutChangingStatus() {
		Incident incident = openWarning();

		incident.escalate(IncidentSeverity.CRITICAL, ESCALATED_AT);

		assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
		assertThat(incident.getUpdatedAt()).isEqualTo(ESCALATED_AT);
	}

	/** 사람이 보고 있는 중에도 상태는 나빠질 수 있고, 그 사실이 전달되어야 한다. */
	@Test
	void escalatesAnAcknowledgedIncidentAndKeepsItAcknowledged() {
		Incident incident = openWarning();
		incident.acknowledge(ACKNOWLEDGED_AT);

		incident.escalate(IncidentSeverity.CRITICAL, ESCALATED_AT);

		assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
	}

	@Test
	void rejectsLoweringTheSeverity() {
		Incident incident = Incident.open(IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL, TRIGGER, OPENED_AT);

		assertThatIllegalStateException().isThrownBy(() -> incident.escalate(IncidentSeverity.WARNING, ESCALATED_AT));

		assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
		assertThat(incident.getUpdatedAt()).isEqualTo(OPENED_AT);
	}

	@Test
	void rejectsEscalatingToTheSameSeverity() {
		Incident incident = openWarning();

		assertThatIllegalStateException().isThrownBy(() -> incident.escalate(IncidentSeverity.WARNING, ESCALATED_AT));
	}

	@Test
	void rejectsEscalatingAResolvedIncident() {
		Incident incident = openWarning();
		incident.resolve(RESOLVED_AT);

		assertThatIllegalStateException().isThrownBy(() -> incident.escalate(IncidentSeverity.CRITICAL, ESCALATED_AT));

		assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.WARNING);
	}

	/**
	 * RESOLVED는 마지막 단계이다. 이 Class에는 그 상태에서 빠져나오는 method가 없고, 상태를 바꾸는
	 * 세 method 모두 거절한 뒤에도 상태가 그대로여야 한다.
	 */
	@Test
	void staysResolvedWhateverIsAttempted() {
		Incident incident = openWarning();
		incident.resolve(RESOLVED_AT);

		assertThatIllegalStateException().isThrownBy(() -> incident.acknowledge(ACKNOWLEDGED_AT));
		assertThatIllegalStateException().isThrownBy(() -> incident.resolve(ESCALATED_AT));
		assertThatIllegalStateException().isThrownBy(() -> incident.escalate(IncidentSeverity.CRITICAL, ESCALATED_AT));

		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
		assertThat(incident.getUpdatedAt()).isEqualTo(RESOLVED_AT);
	}

	/** 상태가 어떻게 흘러가든 Incident를 연 근거는 그대로여야 한다. */
	@Test
	void keepsTheEvidenceThroughEveryTransition() {
		Incident incident = openWarning();

		incident.acknowledge(ACKNOWLEDGED_AT);
		incident.escalate(IncidentSeverity.CRITICAL, ESCALATED_AT);
		incident.resolve(RESOLVED_AT);

		assertThat(incident.getTriggerWindowStart()).isEqualTo(WINDOW_START);
		assertThat(incident.getTriggerWindowEnd()).isEqualTo(OPENED_AT);
		assertThat(incident.getTriggerRequestCount()).isEqualTo(120);
		assertThat(incident.getTriggerErrorCount()).isEqualTo(30);
		assertThat(incident.getTriggerErrorRate()).isEqualTo(new BigDecimal("25.00"));
		assertThat(incident.getTriggerP95LatencyMs()).isEqualTo(240L);
		assertThat(incident.getOpenedAt()).isEqualTo(OPENED_AT);
	}

	@Test
	void ranksCriticalAboveWarning() {
		assertThat(IncidentSeverity.CRITICAL.isHigherThan(IncidentSeverity.WARNING)).isTrue();
		assertThat(IncidentSeverity.WARNING.isHigherThan(IncidentSeverity.CRITICAL)).isFalse();
		assertThat(IncidentSeverity.WARNING.isHigherThan(IncidentSeverity.WARNING)).isFalse();
		assertThat(IncidentSeverity.CRITICAL.isHigherThan(IncidentSeverity.CRITICAL)).isFalse();
	}

	private static Incident openWarning() {
		return Incident.open(IncidentType.ERROR_RATE, IncidentSeverity.WARNING, TRIGGER, OPENED_AT);
	}

}

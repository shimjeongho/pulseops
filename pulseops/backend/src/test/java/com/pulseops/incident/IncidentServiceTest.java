package com.pulseops.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pulseops.incident.dto.IncidentDetailResponse;
import com.pulseops.incident.dto.IncidentSummaryResponse;
import com.pulseops.metric.MetricSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 읽은 Incident를 어떤 순서로, 어떤 값으로 내보내는지를 검증한다.
 * <p>
 * 저장소는 흉내 낸다. 무엇이 처리 중인가는 이 Class가 정하지 않고 조회의 정의를 그대로 쓰므로,
 * 여기서 확인할 것은 그 결과를 어떻게 다루는가뿐이다. 어떤 상태가 걸려 나오고 무엇이 빠지는지는
 * {@link IncidentActiveQueryIntegrationTest}가 실제 MySQL에서 고정한다.
 * <p>
 * 흉내 낸 저장소에 RESOLVED를 담아 그것이 빠지는지 보지 않는다. 그렇게 하면 조회가 이미 걸러낸
 * 것을 이 Class가 한 번 더 거르도록 요구하게 되고, 같은 정의가 두 곳에 생긴다.
 * <p>
 * 저장되지 않은 Incident를 쓰므로 ID는 없다. ID가 실제로 응답에 실리는지는 저장을 거치는
 * {@link IncidentApiIntegrationTest}가 확인한다.
 */
class IncidentServiceTest {

	private static final String SERVICE = "demo-shop";

	private static final String METHOD = "POST";

	private static final String ENDPOINT = "/api/orders";

	private static final Instant WINDOW_START = Instant.parse("2026-09-01T05:59:00Z");

	private static final Instant OPENED_AT = Instant.parse("2026-09-01T06:00:00Z");

	private static final Instant EARLIER = Instant.parse("2026-09-01T05:50:00Z");

	private static final Instant ACKNOWLEDGED_AT = Instant.parse("2026-09-01T06:01:00Z");

	private static final Instant RESOLVED_AT = Instant.parse("2026-09-01T06:05:00Z");

	/**
	 * 확인 처리가 알린 변화를 순서대로 모아 둔다. 알림을 어떻게 내보내는지는 여기서 볼 것이 아니고,
	 * 무엇을 바꿨을 때 알리고 무엇을 거절했을 때 알리지 않는지만 본다.
	 */
	private final List<IncidentChangedEvent> announcedChanges = new ArrayList<>();

	private final ApplicationEventPublisher eventPublisher = (event) -> this.announcedChanges
		.add((IncidentChangedEvent) event);

	private IncidentRepository incidentRepository;

	private IncidentService incidentService;

	@BeforeEach
	void createService() {
		this.incidentRepository = mock(IncidentRepository.class);
		this.incidentService = new IncidentService(this.incidentRepository, this.eventPublisher);
	}

	/** 사람이 확인했다고 장애가 끝난 것은 아니므로 확인된 Incident도 여전히 목록에 있어야 한다. */
	@Test
	void listsBothOpenAndAcknowledgedIncidents() {
		Incident open = open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT);
		Incident acknowledged = open("/api/payments", IncidentSeverity.WARNING, OPENED_AT);
		acknowledged.acknowledge(ACKNOWLEDGED_AT);
		givenActive(open, acknowledged);

		assertThat(this.incidentService.activeIncidents()).extracting(IncidentSummaryResponse::status)
			.containsExactlyInAnyOrder(IncidentStatus.OPEN, IncidentStatus.ACKNOWLEDGED);
	}

	/** 처리 중인 Incident가 없는 것은 시스템이 건강하다는 뜻이며 실패가 아니다. */
	@Test
	void returnsAnEmptyListWhenNothingIsActive() {
		givenActive();

		assertThat(this.incidentService.activeIncidents()).isEmpty();
	}

	/** 목록의 첫 줄은 지금 가장 급한 Incident여야 한다. */
	@Test
	void putsTheHigherSeverityFirst() {
		givenActive(open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT),
				open("/api/payments", IncidentSeverity.CRITICAL, OPENED_AT));

		assertThat(this.incidentService.activeIncidents()).extracting(IncidentSummaryResponse::severity)
			.containsExactly(IncidentSeverity.CRITICAL, IncidentSeverity.WARNING);
	}

	/** 심각도가 같다면 방금 일어난 일이 먼저 눈에 들어와야 한다. */
	@Test
	void putsTheMoreRecentlyOpenedFirstWithinTheSameSeverity() {
		givenActive(open(ENDPOINT, IncidentSeverity.CRITICAL, EARLIER),
				open("/api/payments", IncidentSeverity.CRITICAL, OPENED_AT));

		assertThat(this.incidentService.activeIncidents()).extracting(IncidentSummaryResponse::openedAt)
			.containsExactly(OPENED_AT, EARLIER);
	}

	/**
	 * 심각도가 낮아도 최근에 열린 것이 더 급한 Incident보다 앞서지 않는다. 두 기준의 우선순위가
	 * 뒤집히면 목록의 첫 줄이 가장 급한 Incident가 아니게 된다.
	 */
	@Test
	void neverLetsARecentWarningOutrankAnOlderCritical() {
		givenActive(open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT),
				open("/api/payments", IncidentSeverity.CRITICAL, EARLIER));

		assertThat(this.incidentService.activeIncidents())
			.extracting(IncidentSummaryResponse::severity, IncidentSummaryResponse::openedAt)
			.containsExactly(tuple(IncidentSeverity.CRITICAL, EARLIER), tuple(IncidentSeverity.WARNING, OPENED_AT));
	}

	/**
	 * 판정에 쓴 창의 끝이 곧 openedAt이라 같은 순간에 열린 Incident가 여럿일 수 있다. 그때 순서가
	 * 정해지지 않으면 다시 읽을 때마다 줄의 위치가 바뀐다.
	 */
	@Test
	void ordersBySeriesWhenSeverityAndOpenedAtAreTheSame() {
		givenActive(open("/api/products", IncidentSeverity.CRITICAL, OPENED_AT),
				open("/api/orders", IncidentSeverity.CRITICAL, OPENED_AT));

		assertThat(this.incidentService.activeIncidents()).extracting(IncidentSummaryResponse::endpoint)
			.containsExactly("/api/orders", "/api/products");
	}

	/** 같은 계열이라도 오류율과 응답 시간은 서로 다른 Incident이므로 둘 다 목록에 남아야 한다. */
	@Test
	void ordersByTypeWhenEverythingElseIsTheSame() {
		Incident latency = new IncidentFixture(ENDPOINT, IncidentType.LATENCY, IncidentSeverity.CRITICAL, OPENED_AT)
			.build();
		Incident errorRate = new IncidentFixture(ENDPOINT, IncidentType.ERROR_RATE, IncidentSeverity.CRITICAL,
				OPENED_AT)
			.build();
		givenActive(latency, errorRate);

		assertThat(this.incidentService.activeIncidents()).extracting(IncidentSummaryResponse::type)
			.containsExactly(IncidentType.ERROR_RATE, IncidentType.LATENCY);
	}

	/** 목록은 지금 무엇이 열려 있는지에 답하며 Incident를 연 창의 관측값은 담지 않는다. */
	@Test
	void mapsEverySummaryField() {
		Incident incident = open(ENDPOINT, IncidentSeverity.CRITICAL, OPENED_AT);
		incident.acknowledge(ACKNOWLEDGED_AT);
		givenActive(incident);

		IncidentSummaryResponse summary = this.incidentService.activeIncidents().get(0);

		assertThat(summary.service()).isEqualTo(SERVICE);
		assertThat(summary.method()).isEqualTo(METHOD);
		assertThat(summary.endpoint()).isEqualTo(ENDPOINT);
		assertThat(summary.type()).isEqualTo(IncidentType.ERROR_RATE);
		assertThat(summary.severity()).isEqualTo(IncidentSeverity.CRITICAL);
		assertThat(summary.status()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		assertThat(summary.openedAt()).isEqualTo(OPENED_AT);
		assertThat(summary.acknowledgedAt()).isEqualTo(ACKNOWLEDGED_AT);
	}

	/** 상세는 Incident를 연 창의 관측값까지 그대로 전한다. 그 값이 곧 판정의 근거이다. */
	@Test
	void mapsEveryDetailField() {
		givenFound(open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT));

		IncidentDetailResponse detail = this.incidentService.incidentDetail(1L);

		assertThat(detail.service()).isEqualTo(SERVICE);
		assertThat(detail.method()).isEqualTo(METHOD);
		assertThat(detail.endpoint()).isEqualTo(ENDPOINT);
		assertThat(detail.type()).isEqualTo(IncidentType.ERROR_RATE);
		assertThat(detail.severity()).isEqualTo(IncidentSeverity.WARNING);
		assertThat(detail.status()).isEqualTo(IncidentStatus.OPEN);
		assertThat(detail.openedAt()).isEqualTo(OPENED_AT);
		assertThat(detail.acknowledgedAt()).isNull();
		assertThat(detail.resolvedAt()).isNull();
		assertThat(detail.triggerWindowStart()).isEqualTo(WINDOW_START);
		assertThat(detail.triggerWindowEnd()).isEqualTo(OPENED_AT);
		assertThat(detail.triggerRequestCount()).isEqualTo(120L);
		assertThat(detail.triggerErrorCount()).isEqualTo(30L);
		assertThat(detail.triggerErrorRate()).isEqualByComparingTo("25.00");
		assertThat(detail.triggerP95LatencyMs()).isEqualTo(240L);
	}

	@Test
	void findsTheDetailOfAnAcknowledgedIncident() {
		Incident incident = open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT);
		incident.acknowledge(ACKNOWLEDGED_AT);
		givenFound(incident);

		IncidentDetailResponse detail = this.incidentService.incidentDetail(1L);

		assertThat(detail.status()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		assertThat(detail.acknowledgedAt()).isEqualTo(ACKNOWLEDGED_AT);
		assertThat(detail.resolvedAt()).isNull();
	}

	/**
	 * 끝난 Incident의 기록을 읽는 것이 나중에 무슨 일이 있었는지 돌아보는 방법이다. 목록에서
	 * 사라졌다는 이유로 상세까지 닫히면 그 기록에 닿을 방법이 없어진다.
	 */
	@Test
	void findsTheDetailOfAResolvedIncident() {
		Incident incident = open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT);
		incident.resolve(RESOLVED_AT);
		givenFound(incident);

		IncidentDetailResponse detail = this.incidentService.incidentDetail(1L);

		assertThat(detail.status()).isEqualTo(IncidentStatus.RESOLVED);
		assertThat(detail.resolvedAt()).isEqualTo(RESOLVED_AT);
	}

	/** 표본이 없어 구하지 못한 P95는 null이며 0으로 바뀌지 않는다. */
	@Test
	void keepsAMissingP95AsNull() {
		givenFound(new IncidentFixture(ENDPOINT, IncidentType.LATENCY, IncidentSeverity.WARNING, OPENED_AT)
			.withoutP95()
			.build());

		assertThat(this.incidentService.incidentDetail(1L).triggerP95LatencyMs()).isNull();
	}

	@Test
	void refusesToDescribeAnIncidentThatDoesNotExist() {
		given(this.incidentRepository.findById(404L)).willReturn(Optional.empty());

		assertThatExceptionOfType(IncidentNotFoundException.class)
			.isThrownBy(() -> this.incidentService.incidentDetail(404L));
	}

	/** 확인 처리는 사람이 지금부터 본다는 표시이며 그 시각이 함께 남아야 한다. */
	@Test
	void acknowledgesAnOpenIncident() {
		givenLocked(open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT));

		IncidentDetailResponse detail = this.incidentService.acknowledge(1L);

		assertThat(detail.status()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		assertThat(detail.acknowledgedAt()).isNotNull();
	}

	/** 확인 처리는 잠그고 읽어야 두 요청이 나란히 OPEN을 보고 둘 다 성공하는 일이 없다. */
	@Test
	void readsTheIncidentWithALockBeforeAcknowledging() {
		givenLocked(open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT));

		this.incidentService.acknowledge(1L);

		verify(this.incidentRepository).findByIdForUpdate(1L);
		verify(this.incidentRepository, never()).findById(1L);
	}

	/** 확인 처리가 바꾼 값은 Transaction이 관리하는 상태에 남으므로 따로 저장하지 않는다. */
	@Test
	void neverSavesTheIncidentAgainAfterAcknowledging() {
		givenLocked(open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT));

		this.incidentService.acknowledge(1L);

		verify(this.incidentRepository, never()).save(any());
		verify(this.incidentRepository, never()).saveAndFlush(any());
	}

	@Test
	void refusesToAcknowledgeAnIncidentThatDoesNotExist() {
		given(this.incidentRepository.findByIdForUpdate(404L)).willReturn(Optional.empty());

		assertThatExceptionOfType(IncidentNotFoundException.class)
			.isThrownBy(() -> this.incidentService.acknowledge(404L));
	}

	/**
	 * 두 번째 확인을 성공으로 넘기면 나중 사람이 앞사람이 맡고 있는 Incident를 자기가 맡았다고 믿게
	 * 된다.
	 */
	@Test
	void refusesToAcknowledgeAnAlreadyAcknowledgedIncident() {
		Incident incident = open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT);
		incident.acknowledge(ACKNOWLEDGED_AT);
		givenLocked(incident);

		assertThatExceptionOfType(IncidentAcknowledgeNotAllowedException.class)
			.isThrownBy(() -> this.incidentService.acknowledge(1L));
	}

	/** 끝난 Incident에 사람을 붙이는 것은 뜻이 없다. 아무도 그 장애를 더 보지 않는다. */
	@Test
	void refusesToAcknowledgeAResolvedIncident() {
		Incident incident = open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT);
		incident.resolve(RESOLVED_AT);
		givenLocked(incident);

		assertThatExceptionOfType(IncidentAcknowledgeNotAllowedException.class)
			.isThrownBy(() -> this.incidentService.acknowledge(1L));
	}

	/**
	 * 거절된 확인 처리는 Incident를 조금도 바꾸지 않아야 한다. 일부만 바꾼 뒤 실패하면 되돌릴 것이
	 * Transaction에 남고, 그 되돌림이 실패하는 자리에서는 앞 확인의 시각이 사라진다.
	 */
	@Test
	void leavesTheIncidentUntouchedWhenTheAcknowledgeIsRefused() {
		Incident incident = open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT);
		incident.acknowledge(ACKNOWLEDGED_AT);
		givenLocked(incident);

		assertThatExceptionOfType(IncidentAcknowledgeNotAllowedException.class)
			.isThrownBy(() -> this.incidentService.acknowledge(1L));

		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
		assertThat(incident.getAcknowledgedAt()).isEqualTo(ACKNOWLEDGED_AT);
	}

	/**
	 * 상태 전이를 거부하는 것은 {@link Incident}이며 Service는 그 거부만 옮긴다. 원인을 남겨 두어야
	 * 어떤 상태가 막았는지 나중에 추적할 수 있다.
	 */
	@Test
	void keepsTheDomainRefusalAsTheCauseOfTheApplicationFailure() {
		Incident incident = open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT);
		incident.resolve(RESOLVED_AT);
		givenLocked(incident);

		assertThatExceptionOfType(IncidentAcknowledgeNotAllowedException.class)
			.isThrownBy(() -> this.incidentService.acknowledge(1L))
			.withCauseInstanceOf(IllegalStateException.class);
	}

	/**
	 * 확인 처리는 화면에 보이는 상태를 바꾸므로 알려야 한다. 알림의 시각은 Incident에 남은 확인 시각과
	 * 같은 값이어야 한다. 두 번 읽어 만든 시각은 미세하게 어긋나고, 그 차이는 나중에 설명할 수 없다.
	 */
	@Test
	void announcesTheChangeAfterAcknowledging() {
		givenLocked(open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT));

		IncidentDetailResponse detail = this.incidentService.acknowledge(1L);

		assertThat(this.announcedChanges).containsExactly(new IncidentChangedEvent(1L, detail.acknowledgedAt()));
	}

	/** 거절된 확인은 아무것도 바꾸지 않았으므로 알릴 변화도 없다. */
	@Test
	void saysNothingWhenTheAcknowledgeIsRefused() {
		Incident incident = open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT);
		incident.acknowledge(ACKNOWLEDGED_AT);
		givenLocked(incident);

		assertThatExceptionOfType(IncidentAcknowledgeNotAllowedException.class)
			.isThrownBy(() -> this.incidentService.acknowledge(1L));

		assertThat(this.announcedChanges).isEmpty();
	}

	/** 끝난 Incident에 대한 확인도 마찬가지다. 거절은 상태 변화가 아니다. */
	@Test
	void saysNothingWhenAcknowledgingAResolvedIncident() {
		Incident incident = open(ENDPOINT, IncidentSeverity.WARNING, OPENED_AT);
		incident.resolve(RESOLVED_AT);
		givenLocked(incident);

		assertThatExceptionOfType(IncidentAcknowledgeNotAllowedException.class)
			.isThrownBy(() -> this.incidentService.acknowledge(1L));

		assertThat(this.announcedChanges).isEmpty();
	}

	/** 없는 Incident를 확인하려는 요청은 가리킬 변화 자체가 없다. */
	@Test
	void saysNothingWhenTheIncidentToAcknowledgeDoesNotExist() {
		given(this.incidentRepository.findByIdForUpdate(404L)).willReturn(Optional.empty());

		assertThatExceptionOfType(IncidentNotFoundException.class)
			.isThrownBy(() -> this.incidentService.acknowledge(404L));

		assertThat(this.announcedChanges).isEmpty();
	}

	private void givenActive(Incident... incidents) {
		given(this.incidentRepository.findActive()).willReturn(List.of(incidents));
	}

	private void givenFound(Incident incident) {
		given(this.incidentRepository.findById(1L)).willReturn(Optional.of(incident));
	}

	private void givenLocked(Incident incident) {
		given(this.incidentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(incident));
	}

	private static Incident open(String endpoint, IncidentSeverity severity, Instant openedAt) {
		return new IncidentFixture(endpoint, IncidentType.ERROR_RATE, severity, openedAt).build();
	}

	/**
	 * 정렬은 계열과 종류, 심각도, 열린 시각이 서로 다른 Incident를 여럿 두고 보아야 하므로 그 값들만
	 * 골라 바꾼다. 관측값까지 매번 적으면 무엇이 이 테스트의 조건인지 보이지 않는다.
	 */
	private static final class IncidentFixture {

		private final String endpoint;

		private final IncidentType type;

		private final IncidentSeverity severity;

		private final Instant openedAt;

		private Long p95LatencyMs = 240L;

		private IncidentFixture(String endpoint, IncidentType type, IncidentSeverity severity, Instant openedAt) {
			this.endpoint = endpoint;
			this.type = type;
			this.severity = severity;
			this.openedAt = openedAt;
		}

		private IncidentFixture withoutP95() {
			this.p95LatencyMs = null;
			return this;
		}

		private Incident build() {
			MetricSnapshot trigger = new MetricSnapshot(SERVICE, METHOD, this.endpoint, WINDOW_START, this.openedAt,
					120, 30, 25.0, this.p95LatencyMs);
			return Incident.open(this.type, this.severity, trigger, this.openedAt);
		}

	}

}

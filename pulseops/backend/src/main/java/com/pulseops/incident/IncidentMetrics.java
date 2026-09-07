package com.pulseops.incident;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Incident의 상태를 Prometheus가 읽을 수 있는 형태로 내보낸다.
 * <p>
 * 관측만 한다. 이 Class는 Incident를 열지도 끝내지도 않고 판정에 쓰이는 어떤 값도 만들지 않는다.
 * Incident를 여는 규칙은 {@link IncidentDetector}와 {@link IncidentRule}에만 있으며, 여기를 지워도
 * 탐지 동작은 그대로다.
 * <p>
 * 지금 몇 건이 처리 중인가와 그동안 몇 번 열리고 끝났는가는 성질이 다른 값이라 서로 다른 방식으로
 * 만든다.
 * <p>
 * 처리 중인 건수는 Gauge이며 근거는 언제나 DB이다. 열림과 끝남을 JVM 안에서 더하고 빼서 유지하지
 * 않는다. 그렇게 만든 수는 재시작하면 0에서 다시 시작하고, 사람이 확인 처리한 것이나 이 Instance가
 * 만들지 않은 Incident를 반영하지 못해 시간이 갈수록 실제와 벌어진다. DB는 그 모든 경우에 대해
 * 하나의 답을 갖고 있다.
 * <p>
 * 열림과 끝남은 Counter이며 이 Application이 살아 있는 동안 실제로 일어난 전이만 센다. 재시작하면
 * 0에서 다시 시작하므로 DB에 쌓인 Incident 총계와 같지 않고, 같아야 하는 값도 아니다. Prometheus는
 * Counter가 되돌아가는 것을 재시작으로 알아보고 rate를 이어서 계산한다. 하루에 몇 건이 열렸는지를
 * 권위 있게 세는 것은 Daily Report의 몫이다.
 * <p>
 * 전이는 commit이 끝난 뒤에만 센다. 판정 Transaction은 교착이나 제약 위반으로 되돌아갈 수 있고,
 * 그 안에서 미리 세면 일어나지 않은 장애가 Counter에 남는다. {@link IncidentChangedEventListener}가
 * 알림을 commit 뒤로 미루는 것과 같은 이유이다.
 * <p>
 * Tag는 종류와 심각도뿐이다. Incident ID, 계열의 endpoint, service를 Tag로 두지 않는다. 그것들은
 * Incident가 열릴 때마다 새 값이 될 수 있어 시계열 수가 장애 수만큼 늘어나고, 한 번 만들어진
 * 시계열은 그 Incident가 끝난 뒤에도 남는다. 어느 endpoint가 아픈지는 REST 조회와 SSE가 답한다.
 */
@Component
public class IncidentMetrics {

	static final String ACTIVE_GAUGE = "pulseops.incidents.active";

	static final String OPENED_COUNTER = "pulseops.incidents.opened";

	static final String RESOLVED_COUNTER = "pulseops.incidents.resolved";

	static final String TYPE_TAG = "type";

	static final String SEVERITY_TAG = "severity";

	/**
	 * 처리 중인 건수를 DB에서 다시 세는 최소 간격이다.
	 * <p>
	 * 값을 아끼려는 것이 아니라 한 번의 scrape가 하나의 장면을 뜻하게 하려는 것이다. 종류와 심각도
	 * 조합마다 Gauge가 따로 있어 Prometheus는 한 번 긁을 때 이 값을 네 번 묻는데, 그때마다 DB를
	 * 새로 읽으면 네 숫자가 서로 다른 순간의 것이 되어 그 합이 어느 시점의 처리 중 건수와도 같지
	 * 않을 수 있다.
	 * <p>
	 * scrape 간격보다 짧게 둔다. 이 값이 scrape 간격보다 길면 연속한 두 scrape가 같은 숫자를
	 * 보고하게 되어 Gauge가 실제보다 늦게 움직인다.
	 */
	private static final Duration ACTIVE_SNAPSHOT_TTL = Duration.ofSeconds(2);

	private static final Logger logger = LoggerFactory.getLogger(IncidentMetrics.class);

	private final IncidentRepository incidentRepository;

	private final Counter[] openedCounters;

	private final Counter[] resolvedCounters;

	private final AtomicReference<ActiveIncidentSnapshot> activeSnapshot = new AtomicReference<>();

	public IncidentMetrics(IncidentRepository incidentRepository, MeterRegistry meterRegistry) {
		this.incidentRepository = incidentRepository;
		this.openedCounters = registerOpenedCounters(meterRegistry);
		this.resolvedCounters = registerResolvedCounters(meterRegistry);
		registerActiveGauges(meterRegistry);
	}

	/**
	 * 전이 하나를 센다. commit된 Transaction에서 발행된 Event만 여기에 닿는다.
	 * <p>
	 * Transaction 밖에서 발행된 Event는 Spring이 이 method를 부르지 않고 버린다. 그것이 맞다.
	 * Incident의 전이는 언제나 Transaction 안에서 일어나므로, 밖에서 온 Event는 저장되지 않은 전이를
	 * 뜻한다.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onIncidentLifecycle(IncidentLifecycleEvent event) {
		switch (event.transition()) {
			case OPENED -> this.openedCounters[counterIndex(event.type(), event.severity())].increment();
			case RESOLVED -> this.resolvedCounters[event.type().ordinal()].increment();
		}
	}

	/**
	 * 열린 건수는 심각도까지 나눠 센다. 열릴 때의 심각도는 그 뒤로 바뀌지 않으므로 이 Tag의 뜻이
	 * 시간이 지나도 흔들리지 않는다.
	 */
	private static Counter[] registerOpenedCounters(MeterRegistry meterRegistry) {
		Counter[] counters = new Counter[IncidentType.values().length * IncidentSeverity.values().length];
		for (IncidentType type : IncidentType.values()) {
			for (IncidentSeverity severity : IncidentSeverity.values()) {
				counters[counterIndex(type, severity)] = Counter.builder(OPENED_COUNTER)
					.description("Incidents opened while this application instance has been running")
					.tag(TYPE_TAG, type.name())
					.tag(SEVERITY_TAG, severity.name())
					.register(meterRegistry);
			}
		}
		return counters;
	}

	/**
	 * 끝난 건수에는 심각도를 붙이지 않는다.
	 * <p>
	 * 심각도는 Incident가 열린 뒤 올라갈 수 있어, WARNING으로 열린 Incident가 CRITICAL로 끝날 수 있다.
	 * 그러면 심각도별로 열림과 끝남을 나란히 놓았을 때 WARNING은 끝나지 않은 것이 쌓이고 CRITICAL은
	 * 열린 적 없는 것이 끝난 것처럼 보인다. 붙일 수 있다는 이유로 붙인 Tag가 대시보드에서 틀린 뺄셈을
	 * 부르는 것보다, 종류만으로 세고 지금 남아 있는 것은 Gauge에게 묻는 편이 낫다.
	 */
	private static Counter[] registerResolvedCounters(MeterRegistry meterRegistry) {
		Counter[] counters = new Counter[IncidentType.values().length];
		for (IncidentType type : IncidentType.values()) {
			counters[type.ordinal()] = Counter.builder(RESOLVED_COUNTER)
				.description("Incidents resolved while this application instance has been running")
				.tag(TYPE_TAG, type.name())
				.register(meterRegistry);
		}
		return counters;
	}

	/**
	 * 있을 수 있는 조합을 모두 미리 등록한다.
	 * <p>
	 * 처리 중인 Incident가 생겼을 때 그 조합의 Gauge를 만드는 방식이면, 아무 일도 없는 구간에서 시계열
	 * 자체가 없어 대시보드가 "0건"과 "관측되지 않음"을 구분하지 못한다. 조합은 종류 둘과 심각도 둘로
	 * 고정되어 있어 미리 만들어도 네 줄이다.
	 */
	private void registerActiveGauges(MeterRegistry meterRegistry) {
		for (IncidentType type : IncidentType.values()) {
			for (IncidentSeverity severity : IncidentSeverity.values()) {
				Gauge.builder(ACTIVE_GAUGE, this, (metrics) -> metrics.activeCount(type, severity))
					.description("Incidents currently OPEN or ACKNOWLEDGED, counted from the database")
					.tag(TYPE_TAG, type.name())
					.tag(SEVERITY_TAG, severity.name())
					.strongReference(true)
					.register(meterRegistry);
			}
		}
	}

	/**
	 * DB를 읽지 못하면 0이 아니라 NaN을 내보낸다.
	 * <p>
	 * 0으로 답하면 DB 장애가 대시보드에서 "처리 중인 장애 없음"으로 보인다. NaN은 Prometheus에서 값이
	 * 없는 구간으로 남아 그래프가 끊기므로, 읽지 못했다는 사실이 그대로 드러난다.
	 * <p>
	 * 조회 결과에 없는 조합은 0이다. 그 조합의 Incident가 하나도 없다는 뜻이며, 읽지 못한 것과 다르다.
	 */
	private double activeCount(IncidentType type, IncidentSeverity severity) {
		List<ActiveIncidentCount> counts = currentSnapshot().counts();
		if (counts == null) {
			return Double.NaN;
		}
		return counts.stream()
			.filter((count) -> count.type() == type && count.severity() == severity)
			.mapToLong(ActiveIncidentCount::count)
			.findFirst()
			.orElse(0L);
	}

	/**
	 * 실패도 snapshot으로 남긴다. 그래야 DB가 응답하지 않는 동안 한 번의 scrape가 같은 실패를 네 번
	 * 반복해서 시도하지 않는다.
	 */
	private ActiveIncidentSnapshot currentSnapshot() {
		Instant now = Instant.now();
		ActiveIncidentSnapshot snapshot = this.activeSnapshot.get();
		if (snapshot != null && !snapshot.isOlderThan(ACTIVE_SNAPSHOT_TTL, now)) {
			return snapshot;
		}
		ActiveIncidentSnapshot read = readActiveCounts(now);
		this.activeSnapshot.set(read);
		return read;
	}

	/**
	 * 예외를 밖으로 보내지 않는다. Gauge를 읽다 나간 예외는 scrape 응답 전체를 실패시켜, DB와 아무
	 * 관계없는 JVM과 HTTP metric까지 함께 사라지게 한다.
	 */
	private ActiveIncidentSnapshot readActiveCounts(Instant now) {
		try {
			return new ActiveIncidentSnapshot(this.incidentRepository.countActiveByTypeAndSeverity(), now);
		}
		catch (RuntimeException ex) {
			logger.warn("처리 중인 Incident 수를 읽지 못해 metric을 비운다. reason={}", ex.getClass().getSimpleName());
			return new ActiveIncidentSnapshot(null, now);
		}
	}

	private static int counterIndex(IncidentType type, IncidentSeverity severity) {
		return type.ordinal() * IncidentSeverity.values().length + severity.ordinal();
	}

	/**
	 * @param counts 조회에 성공했을 때의 결과이며, 실패했으면 null이다. 빈 목록은 처리 중인 Incident가
	 * 하나도 없다는 뜻이라 실패와 같은 값으로 둘 수 없다.
	 * @param readAt 이 값을 읽거나 읽으려다 실패한 시각
	 */
	private record ActiveIncidentSnapshot(List<ActiveIncidentCount> counts, Instant readAt) {

		boolean isOlderThan(Duration ttl, Instant now) {
			return this.readAt.plus(ttl).isBefore(now);
		}

	}

}

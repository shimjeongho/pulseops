package com.pulseops.incident;

import com.pulseops.metric.MetricSeries;
import com.pulseops.metric.MetricSnapshot;
import com.pulseops.metric.MetricWindow;
import com.pulseops.metric.RollingMetricService;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 한 계열의 마지막으로 닫힌 창을 판정해 Incident를 열고, 올리고, 끝낸다.
 * <p>
 * 지금까지 따로 있던 넷을 하나의 흐름으로 잇는 자리다. 창의 값은 {@link RollingMetricService}가,
 * 그 창이 이상인지는 {@link IncidentRule}이, 이상이 몇 번 연속됐는지는 {@link DetectionState}가,
 * 그 연속으로 무엇이 달라졌는지는 {@link Incident}가 안다. 여기서는 그 중 어느 것도 다시 계산하지
 * 않고, 언제 무엇을 부를지와 그 결과를 어떤 경계 안에서 남길지만 정한다.
 * <p>
 * 판정 대상은 언제나 마지막으로 닫힌 창이다. 지금 채워지는 중인 Bucket은 값이 아직 들어오고 있어
 * 같은 구간을 볼 때마다 다른 오류율이 나온다. 그 값으로 연속을 세면 한 구간이 이상과 정상 사이를
 * 오가며 여러 번 세어진다.
 * <p>
 * 같은 창을 두 번 세지 않는 근거는 {@link DetectionState}의 cursor 하나뿐이다. JVM 안의 기억은
 * 재시작이나 Instance 추가로 사라지는데 DB의 상태는 남으므로, 그것을 근거로 삼으면 두 기준이
 * 어긋난 뒤 어느 쪽이 맞는지 말할 수 없게 된다.
 * <p>
 * Redis 읽기는 Transaction 밖에 둔다. 안에 넣으면 Redis가 느릴 때 MySQL 행 잠금이 그 시간만큼 함께
 * 붙들려, Metric 저장소의 지연이 다른 처리의 Detection까지 막는다.
 * <p>
 * 잠금은 언제나 종류 선언 순서대로, 종류 안에서는 {@link DetectionState} 다음 {@link Incident}
 * 순서로 건다. 두 처리가 서로 반대 순서로 잠그면 서로를 기다리다 둘 다 멈춘다.
 * <p>
 * 시각은 스스로 읽지 않고 호출자가 넘긴다. 어느 시점을 기준으로 판정했는지가 결과를 통째로 바꾸므로
 * 그 시점은 테스트가 정할 수 있어야 한다.
 */
@Service
public class IncidentDetector {

	/**
	 * 이만큼 연속으로 이상이어야 Incident를 연다. {@link DetectionState}가 연속 횟수를 같은 값에서
	 * 멈추게 하지만 그것은 값이 끝없이 커지지 않게 하는 저장 쪽 사정이고, 몇 번이면 Incident를 열
	 * 것인가는 여기서 정하는 판단이다.
	 */
	private static final int ABNORMAL_TRIGGER_COUNT = 2;

	/**
	 * 이만큼 연속으로 정상이어야 Incident를 끝낸다. 여는 기준보다 길게 잡아 회복을 더 신중하게
	 * 확인한다. 잘못 여는 것보다 잘못 닫는 것이 위험하다. 닫힌 뒤에는 아무도 그 장애를 보지 않는다.
	 */
	private static final int RECOVERY_TRIGGER_COUNT = 3;

	private static final Logger logger = LoggerFactory.getLogger(IncidentDetector.class);

	private final RollingMetricService rollingMetricService;

	private final DetectionStateRepository detectionStateRepository;

	private final IncidentRepository incidentRepository;

	private final TransactionTemplate transactionTemplate;

	public IncidentDetector(RollingMetricService rollingMetricService,
			DetectionStateRepository detectionStateRepository, IncidentRepository incidentRepository,
			TransactionTemplate transactionTemplate) {
		this.rollingMetricService = rollingMetricService;
		this.detectionStateRepository = detectionStateRepository;
		this.incidentRepository = incidentRepository;
		this.transactionTemplate = transactionTemplate;
	}

	/**
	 * 한 계열의 마지막으로 닫힌 창을 판정하고 그 결과를 남긴다.
	 * <p>
	 * Redis를 읽지 못하거나 MySQL이 응답하지 않으면 이번 판정을 건너뛰고 예외를 밖으로 내보내지
	 * 않는다. 관측을 남기지 못하는 것은 손실이지만, 그 때문에 Kafka 소비가 같은 자리에서 멈추면
	 * 그 뒤의 모든 Metric까지 함께 잃는다.
	 * <p>
	 * 건너뛴 창은 cursor를 올리지 않으므로 사라지지 않는다. Redis에 Bucket이 남아 있는 동안 다음
	 * 판정이 같은 창을 다시 읽을 수 있어, 실패의 대가는 유실이 아니라 지연이다.
	 */
	public void detect(MetricSeries series, Instant evaluationNow) {
		MetricSnapshot snapshot;
		try {
			snapshot = this.rollingMetricService.snapshot(series, lastClosedWindowReference(evaluationNow));
		}
		catch (DataAccessException ex) {
			logger.warn("Redis에서 Metric을 읽지 못해 Incident 판정을 건너뛴다. series={} evaluationNow={} reason={}", series,
					evaluationNow, ex.getClass().getSimpleName());
			return;
		}
		try {
			this.transactionTemplate.executeWithoutResult((status) -> evaluate(series, snapshot));
		}
		// MySQL 쪽 실패만 흡수한다. 여기에는 최초 상태를 만들 때의 identity 충돌도 들어간다.
		// RuntimeException까지 잡으면 판정 규칙의 결함이 조용히 묻히고, 열려야 할 Incident가 열리지
		// 않은 것을 아무도 알 수 없게 된다.
		catch (DataAccessException ex) {
			logger.warn("MySQL에 판정 결과를 남기지 못해 Incident 판정을 건너뛴다. series={} windowEnd={} reason={}", series,
					snapshot.windowEnd(), ex.getClass().getSimpleName());
		}
	}

	/**
	 * 마지막으로 닫힌 창을 읽기 위해 {@link RollingMetricService}에 넘길 기준 시각이다.
	 * <p>
	 * 그 Service는 기준 시각이 속한 Bucket까지 포함해 창을 만든다. 그래서 지금 채워지는 중인 Bucket의
	 * 한 칸 앞을 기준으로 삼으면 창의 끝이 그 Bucket의 시작과 같아져, 아직 닫히지 않은 구간이 통째로
	 * 빠진다.
	 * <p>
	 * 현재 Bucket의 시작에서 1나노초를 빼는 식으로 경계를 흉내 내지 않는다. 그렇게 얻은 시각은 어느
	 * Bucket에도 온전히 속하지 않는 값이라 경계 규칙이 조금만 바뀌어도 다른 창을 가리킨다.
	 */
	private static Instant lastClosedWindowReference(Instant evaluationNow) {
		return MetricWindow.bucketStartOf(evaluationNow).minusSeconds(MetricWindow.BUCKET_SECONDS);
	}

	/**
	 * 한 창을 종류마다 판정한다.
	 * <p>
	 * 두 종류를 한 Transaction에서 처리한다. 나누면 오류율은 반영됐는데 응답 시간은 반영되지 않은
	 * 중간 상태가 남고, 그 사이에 멈추면 두 상태의 cursor가 어긋난 채로 이어진다.
	 * <p>
	 * 선언 순서대로 도는 것이 잠금 순서의 계약이다.
	 */
	private void evaluate(MetricSeries series, MetricSnapshot snapshot) {
		for (IncidentType type : IncidentType.values()) {
			evaluate(series, snapshot, type);
		}
	}

	/**
	 * 종류 하나의 상태를 갱신하고 그 결과로 Incident를 바꾼다.
	 * <p>
	 * 종류마다 상태가 따로 있으므로 한쪽이 건너뛰어도 다른 쪽은 그대로 진행한다. 오류율이 이미 본
	 * 창이라고 해서 응답 시간까지 건너뛰면, 두 상태의 cursor가 어긋난 뒤로 영영 함께 움직이지 않는다.
	 */
	private void evaluate(MetricSeries series, MetricSnapshot snapshot, IncidentType type) {
		DetectionState state = lockOrInitialize(series, type, snapshot.windowEnd());
		// 이미 본 창이면 여기서 끝낸다. 같은 창이 다시 오는 것은 at-least-once 전달에서 정상이므로
		// 상태에 넘겨 예외로 만들지 않는다. 거르는 것은 cursor의 일이고, 그래도 넘어온 창을 거절하는
		// 것은 상태가 스스로를 지키는 마지막 방어이다.
		if (!snapshot.windowEnd().isAfter(state.getLastEvaluatedWindowEnd())) {
			return;
		}
		WindowVerdict verdict = IncidentRule.evaluate(type, snapshot);
		state.apply(snapshot.windowEnd(), verdict);
		switch (verdict.outcome()) {
			case ABNORMAL -> {
				if (state.getAbnormalConsecutive() >= ABNORMAL_TRIGGER_COUNT) {
					openOrEscalate(series, type, verdict.severity(), snapshot);
				}
			}
			case HEALTHY -> {
				if (state.getRecoveryConsecutive() >= RECOVERY_TRIGGER_COUNT) {
					resolveActive(series, type, snapshot.windowEnd());
				}
			}
			// 트래픽이 없거나 P95를 구하지 못한 창(INCONCLUSIVE)과 기준선 사이의 창(NEUTRAL)에서는
			// 처리 중인 Incident를 그대로 둔다. 좋아졌다는 근거가 없는데 닫으면 아직 아픈 서비스가
			// 조용해진다. 연속 횟수는 상태가 이미 끊어 두었다.
			case NEUTRAL, INCONCLUSIVE -> {
			}
		}
	}

	/**
	 * 종류의 상태를 잠그고 읽거나, 아직 없으면 새로 만든다.
	 * <p>
	 * 없는 행은 잠글 수 없다. 그래서 같은 계열을 처음 보는 두 처리가 나란히 없음을 확인하고 각자
	 * 만들 수 있으며, 그 자리를 막는 것은 {@code uk_detection_state_series_type}이다. 충돌한 쪽은
	 * 이번 판정을 잃고 Transaction이 통째로 되돌아간다.
	 * <p>
	 * 충돌한 뒤 같은 Transaction에서 다시 조회하지 않는다. 제약을 어긴 Transaction은 이미 되돌리기로
	 * 정해진 상태라 그 안의 조회에 뜻이 없다. 다시 시도할 자리를 여기에 만들지 않는 것은, 그 재시도가
	 * 무엇을 다시 하는 것인지가 Kafka 재처리 방식이 정해진 뒤에야 분명해지기 때문이다. 지금은 다음
	 * 창의 판정이 이미 만들어진 행을 읽어 이어 간다.
	 * <p>
	 * 새 상태의 cursor는 이 창의 한 Bucket 앞에서 시작하므로 지금 창은 건너뛰이지 않고 새 연속의
	 * 첫 창이 된다.
	 */
	private DetectionState lockOrInitialize(MetricSeries series, IncidentType type, Instant windowEnd) {
		return this.detectionStateRepository.findForUpdate(series.service(), series.method(), series.endpoint(), type)
			.orElseGet(() -> this.detectionStateRepository.save(DetectionState.initialize(series, type, windowEnd)));
	}

	/**
	 * 이상이 기준만큼 연속됐을 때 Incident를 열거나 심각도를 올린다.
	 * <p>
	 * 새로 열 때의 근거는 방금 판정한 창이다. 연속을 완성한 창이 무엇을 보고 있는지가 사람이 가장
	 * 먼저 확인할 값이고, 앞 창의 값을 남기면 Incident가 열린 시각과 그 안의 숫자가 서로 다른 구간을
	 * 가리킨다. 심각도도 같은 이유로 이 창의 심각도이다.
	 * <p>
	 * 이미 처리 중인 Incident가 있으면 새로 만들지 않는다. UNIQUE에 기대어 일단 만들어 보고 충돌을
	 * 받는 방식은 정상 동작에 예외를 쓰는 것이라, 진짜 문제로 인한 충돌과 구분되지 않는다.
	 * <p>
	 * 심각도는 올라갈 때만 손댄다. 이때는 연속을 다시 세지 않는다. 이미 열린 장애가 더 나빠진 것은
	 * 한 창만으로도 알려야 할 사실이고, 사람이 확인 중인 Incident도 악화 사실은 받아야 한다.
	 */
	private void openOrEscalate(MetricSeries series, IncidentType type, IncidentSeverity observed,
			MetricSnapshot snapshot) {
		Optional<Incident> active = lockActive(series, type);
		if (active.isEmpty()) {
			this.incidentRepository.save(Incident.open(type, observed, snapshot, snapshot.windowEnd()));
			return;
		}
		Incident incident = active.get();
		if (observed.isHigherThan(incident.getSeverity())) {
			incident.escalate(observed, snapshot.windowEnd());
		}
	}

	/**
	 * 회복이 기준만큼 연속됐을 때 처리 중인 Incident를 끝낸다. 사람이 확인했는지는 보지 않는다.
	 * 확인 여부는 사람의 일이고 회복은 서비스의 사실이다.
	 * <p>
	 * 끝낼 Incident가 없을 수도 있다. 이상이 연속 기준에 닿기 전에 회복된 계열이 그렇다.
	 */
	private void resolveActive(MetricSeries series, IncidentType type, Instant resolvedAt) {
		lockActive(series, type).ifPresent((incident) -> incident.resolve(resolvedAt));
	}

	/**
	 * 처리 중인 Incident를 잠그고 읽는다. 언제나 상태를 잠근 뒤에만 부른다.
	 * <p>
	 * 판정 결과와 무관하게 같은 자리에서 잠그기 위해 조회를 이 한 곳으로 모은다. 여는 경로와 끝내는
	 * 경로가 서로 다른 순서로 잠그면 두 흐름이 맞물려 멈출 수 있다.
	 */
	private Optional<Incident> lockActive(MetricSeries series, IncidentType type) {
		return this.incidentRepository.findActiveForUpdate(series.service(), series.method(), series.endpoint(), type);
	}

}

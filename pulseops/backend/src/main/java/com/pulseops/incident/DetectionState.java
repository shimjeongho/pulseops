package com.pulseops.incident;

import com.pulseops.metric.MetricSeries;
import com.pulseops.metric.MetricWindow;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 한 계열의 한 종류에 대해 창 판정이 몇 번 연속됐는지를 기억한다.
 * <p>
 * 창 하나가 나쁘다고 바로 Incident를 열지 않으므로 앞 창의 판정을 어딘가 남겨야 한다. 그 기억을
 * Redis에 두지 않는 이유는 TTL이나 재시작으로 사라지기 때문이다. 연속이 조용히 끊겨 열리지 않은
 * Incident는 나중에 되살릴 수 없다.
 * <p>
 * 계열과 종류가 곧 identity이다. 오류율과 응답 시간은 나빠지는 시점도 회복되는 시점도 달라 하나의
 * 연속 횟수로 셀 수 없다.
 * <p>
 * 이 Class는 자기 상태만 바꾼다. 연속 횟수가 기준에 닿았을 때 Incident를 열지 말지는 여기서 정하지
 * 않는다. 심각도도 담지 않는다. WARNING이든 CRITICAL이든 "이상이었다"는 사실은 같고, 그 둘의 차이는
 * Incident를 열거나 올릴 때 쓰이지 연속을 세는 데 쓰이지 않는다.
 * <p>
 * 시각은 벽시계를 읽지 않고 전부 평가한 창의 끝을 쓴다. 상태가 어느 구간까지 반영됐는지가 곧 이
 * 기록의 뜻이므로, 저장 시각을 따로 남기면 두 시간축이 섞인다. 재처리로 과거 구간을 다시 볼 때도
 * 결과가 실행 시점에 좌우되지 않는다.
 */
@Entity
@Table(name = "detection_state")
public class DetectionState {

	/**
	 * 연속 횟수를 여기까지만 센다. Incident를 여는 기준이 2회 연속이라 3 이상은 뜻이 같고, 상한이
	 * 없으면 오래 나쁜 계열의 값이 끝없이 커진다.
	 */
	private static final int ABNORMAL_TRIGGER_COUNT = 2;

	/** 회복 기준은 3회 연속이며 상한을 두는 이유는 {@link #ABNORMAL_TRIGGER_COUNT}와 같다. */
	private static final int RECOVERY_TRIGGER_COUNT = 3;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "service", nullable = false, length = 60)
	private String service;

	@Column(name = "method", nullable = false, length = 10)
	private String method;

	@Column(name = "endpoint", nullable = false, length = 255)
	private String endpoint;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 20)
	private IncidentType type;

	@Column(name = "last_evaluated_window_end", nullable = false)
	private Instant lastEvaluatedWindowEnd;

	@Column(name = "abnormal_consecutive", nullable = false)
	private int abnormalConsecutive;

	@Column(name = "recovery_consecutive", nullable = false)
	private int recoveryConsecutive;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected DetectionState() {
		// JPA 전용 기본 생성자
	}

	/**
	 * 아직 아무 창도 평가하지 않은 상태를 만든다. {@code firstWindowEnd}는 이 상태로 곧 평가할 창의
	 * 끝이며, 이 method는 그 창을 반영하지 않는다.
	 * <p>
	 * 그래서 cursor를 {@code firstWindowEnd}가 아니라 한 Bucket 앞에 둔다. cursor를 평가할 창과 같게
	 * 두면 그 창이 "이미 본 창"이 되어 맨 처음 창이 통째로 사라진다. 한 Bucket 앞이면 첫 창이 바로
	 * 다음 연속 창이 되어 정상적인 첫 평가가 된다.
	 * <p>
	 * 이 cursor는 그 창을 실제로 평가했다는 뜻이 아니라 첫 평가를 가능하게 하는 출발점이다. 열을
	 * nullable로 바꿔 "아직 없음"을 표현하지 않은 것은, 그렇게 하면 cursor를 읽는 모든 자리가 null
	 * 분기를 갖게 되고 연속 판정에도 예외 경로가 하나 더 생기기 때문이다.
	 */
	public static DetectionState initialize(MetricSeries series, IncidentType type, Instant firstWindowEnd) {
		DetectionState state = new DetectionState();
		state.service = series.service();
		state.method = series.method();
		state.endpoint = series.endpoint();
		state.type = type;
		state.lastEvaluatedWindowEnd = firstWindowEnd.minusSeconds(MetricWindow.BUCKET_SECONDS);
		state.abnormalConsecutive = 0;
		state.recoveryConsecutive = 0;
		state.updatedAt = state.lastEvaluatedWindowEnd;
		return state;
	}

	/**
	 * 한 창의 판정을 반영한다.
	 * <p>
	 * 이미 본 창이나 과거 창은 거절한다. 조용히 무시하면 부르는 쪽은 반영된 줄 알고, 중복 처리로
	 * 연속 횟수가 잘못 오르는 것과 같은 일이 아무 흔적 없이 지나간다. 같은 창이 다시 도착하는 것은
	 * Kafka가 at-least-once인 이상 정상이므로, 그 걸러 내기는 이 method를 부르기 전에 cursor로 한다.
	 * <p>
	 * 창이 정확히 한 Bucket 뒤가 아니면 그 사이에 보지 못한 구간이 있다는 뜻이다. 보지 못한 구간이
	 * 어땠는지 모르므로 그 앞의 연속은 근거가 되지 못해 끊는다. 다만 지금 창까지 버리지는 않는다.
	 * 지금 창은 실제로 관측한 창이므로 새 연속의 첫 창이 된다.
	 */
	public void apply(Instant windowEnd, WindowVerdict verdict) {
		if (!windowEnd.isAfter(this.lastEvaluatedWindowEnd)) {
			throw new IllegalStateException("이미 평가한 창이거나 과거 창이다: lastEvaluatedWindowEnd="
					+ this.lastEvaluatedWindowEnd + " windowEnd=" + windowEnd);
		}
		boolean consecutive = windowEnd.equals(this.lastEvaluatedWindowEnd.plusSeconds(MetricWindow.BUCKET_SECONDS));
		int abnormalBefore = consecutive ? this.abnormalConsecutive : 0;
		int recoveryBefore = consecutive ? this.recoveryConsecutive : 0;
		this.abnormalConsecutive = nextAbnormalConsecutive(verdict.outcome(), abnormalBefore);
		this.recoveryConsecutive = nextRecoveryConsecutive(verdict.outcome(), recoveryBefore);
		this.lastEvaluatedWindowEnd = windowEnd;
		this.updatedAt = windowEnd;
	}

	/**
	 * 기본 분기를 두지 않아 판정 종류가 늘어나면 여기서 compile이 깨진다. 새 종류가 조용히 연속을
	 * 잇거나 끊는 것보다 낫다.
	 */
	private static int nextAbnormalConsecutive(WindowVerdict.Outcome outcome, int abnormalBefore) {
		return switch (outcome) {
			case ABNORMAL -> Math.min(abnormalBefore + 1, ABNORMAL_TRIGGER_COUNT);
			case HEALTHY, NEUTRAL, INCONCLUSIVE -> 0;
		};
	}

	/**
	 * HEALTHY만 회복을 잇는다. 트래픽이 없거나 P95를 구하지 못한 창(INCONCLUSIVE)과 기준선 사이의
	 * 창(NEUTRAL)은 좋아졌다는 근거가 아니므로, 그것으로 회복이 쌓이면 아직 아픈 서비스의 Incident가
	 * 닫힌다.
	 */
	private static int nextRecoveryConsecutive(WindowVerdict.Outcome outcome, int recoveryBefore) {
		return switch (outcome) {
			case HEALTHY -> Math.min(recoveryBefore + 1, RECOVERY_TRIGGER_COUNT);
			case ABNORMAL, NEUTRAL, INCONCLUSIVE -> 0;
		};
	}

	public Long getId() {
		return this.id;
	}

	public String getService() {
		return this.service;
	}

	public String getMethod() {
		return this.method;
	}

	public String getEndpoint() {
		return this.endpoint;
	}

	public IncidentType getType() {
		return this.type;
	}

	public Instant getLastEvaluatedWindowEnd() {
		return this.lastEvaluatedWindowEnd;
	}

	public int getAbnormalConsecutive() {
		return this.abnormalConsecutive;
	}

	public int getRecoveryConsecutive() {
		return this.recoveryConsecutive;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

}

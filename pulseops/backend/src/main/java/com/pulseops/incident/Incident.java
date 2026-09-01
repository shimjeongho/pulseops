package com.pulseops.incident;

import com.pulseops.metric.MetricSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 한 계열에서 한 종류의 이상이 관측되어 열린 기록이다. 사람이 보고 관리하는 영구 기록이며 실시간
 * Metric과 달리 사라지지 않는다.
 * <p>
 * 상태와 심각도는 setter가 아니라 {@link #acknowledge}, {@link #resolve}, {@link #escalate}로만
 * 바뀐다. 어떤 전이가 허용되는지를 아는 곳이 여기 하나여야, 나중에 API나 Detector가 늘어나도 같은
 * 규칙이 지켜진다. 특히 RESOLVED는 되돌릴 수 없고 이 Class에는 그 상태에서 빠져나오는 method가
 * 아예 없다.
 * <p>
 * trigger로 시작하는 값들은 이 Incident를 연 창의 관측값이며 생성 이후 바뀌지 않는다. 심각도가
 * 올라가도 그대로 두어 "열렸을 때 무엇을 봤는가"가 남는다. 지금 이 순간의 값은 Redis가 들고 있으므로
 * 여기에 최신 값을 계속 덮어쓰면 두 숫자의 뜻이 섞인다.
 * <p>
 * 시각은 스스로 읽지 않고 전부 호출자가 넘긴다. Metric 창 경계에서 열리고 닫히는 기록이라
 * 그 시각을 정하는 것은 Incident가 아니며, 시계를 안에서 읽으면 전이 시각을 테스트가 정할 수 없다.
 * <p>
 * Schema는 Flyway가 관리한다. 활성 Incident의 유일성을 지키는 {@code active_key}는 DB가 다른 열에서
 * 파생시키는 생성 열이라 여기에 mapping하지 않는다.
 */
@Entity
@Table(name = "incident")
public class Incident {

	/** {@code trigger_error_rate}가 DECIMAL(5, 2)이므로 저장 전에 소수 둘째 자리까지로 맞춘다. */
	private static final int ERROR_RATE_SCALE = 2;

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

	@Enumerated(EnumType.STRING)
	@Column(name = "severity", nullable = false, length = 10)
	private IncidentSeverity severity;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 15)
	private IncidentStatus status;

	@Column(name = "opened_at", nullable = false)
	private Instant openedAt;

	@Column(name = "acknowledged_at")
	private Instant acknowledgedAt;

	@Column(name = "resolved_at")
	private Instant resolvedAt;

	/** 심각도 상승은 자기 시각을 따로 갖지 않으므로, 그 일이 있었다는 사실은 여기에만 남는다. */
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "trigger_window_start", nullable = false)
	private Instant triggerWindowStart;

	@Column(name = "trigger_window_end", nullable = false)
	private Instant triggerWindowEnd;

	@Column(name = "trigger_request_count", nullable = false)
	private long triggerRequestCount;

	@Column(name = "trigger_error_count", nullable = false)
	private long triggerErrorCount;

	@Column(name = "trigger_error_rate", nullable = false, precision = 5, scale = 2)
	private BigDecimal triggerErrorRate;

	/** 표본이 없어 P95를 구할 수 없었던 창이 있으므로 값이 없을 수 있다. */
	@Column(name = "trigger_p95_latency_ms")
	private Long triggerP95LatencyMs;

	protected Incident() {
		// JPA 전용 기본 생성자
	}

	/**
	 * 관측된 창을 근거로 새 Incident를 연다.
	 * <p>
	 * 계열과 관측값을 낱개로 받지 않고 {@link MetricSnapshot}을 그대로 받는다. 이 Snapshot이 이미
	 * 계열과 창 경계, 요청 수, 오류 수, 오류율, P95를 모두 담고 있어 열두 개의 인자를 늘어놓는 것과
	 * 담기는 값이 완전히 같기 때문이다. 같은 Type의 인자가 여럿인 긴 목록은 순서가 뒤바뀌어도
	 * compile되므로, 창의 시작과 끝이나 요청 수와 오류 수가 조용히 뒤집힐 수 있다.
	 */
	public static Incident open(IncidentType type, IncidentSeverity severity, MetricSnapshot trigger,
			Instant openedAt) {
		Incident incident = new Incident();
		incident.service = trigger.service();
		incident.method = trigger.method();
		incident.endpoint = trigger.endpoint();
		incident.type = type;
		incident.severity = severity;
		incident.status = IncidentStatus.OPEN;
		incident.openedAt = openedAt;
		incident.updatedAt = openedAt;
		incident.triggerWindowStart = trigger.windowStart();
		incident.triggerWindowEnd = trigger.windowEnd();
		incident.triggerRequestCount = trigger.requestCount();
		incident.triggerErrorCount = trigger.errorCount();
		incident.triggerErrorRate = BigDecimal.valueOf(trigger.errorRate())
			.setScale(ERROR_RATE_SCALE, RoundingMode.HALF_UP);
		incident.triggerP95LatencyMs = trigger.p95LatencyMs();
		return incident;
	}

	/** 사람이 보고 있다는 표시이다. 아직 아무도 확인하지 않은 Incident에만 붙일 수 있다. */
	public void acknowledge(Instant now) {
		if (this.status != IncidentStatus.OPEN) {
			throw new IllegalStateException("확인 처리는 OPEN Incident만 가능하다: status=" + this.status);
		}
		this.status = IncidentStatus.ACKNOWLEDGED;
		this.acknowledgedAt = now;
		this.updatedAt = now;
	}

	/**
	 * Incident를 끝낸다. 사람이 확인했는지와 무관하게 끝낼 수 있으며 acknowledgedAt은 그대로 둔다.
	 * 확인을 거쳤는지는 지워지면 안 되는 사실이다.
	 */
	public void resolve(Instant now) {
		if (this.status == IncidentStatus.RESOLVED) {
			throw new IllegalStateException("이미 해결된 Incident는 다시 해결할 수 없다: status=" + this.status);
		}
		this.status = IncidentStatus.RESOLVED;
		this.resolvedAt = now;
		this.updatedAt = now;
	}

	/**
	 * 더 나쁜 상태가 관측되었을 때 심각도만 올린다. 상태는 건드리지 않으므로 확인 처리된 Incident도
	 * 악화 사실을 받을 수 있다.
	 * <p>
	 * 내리는 전이는 없다. 심각도는 이 Incident가 도달했던 가장 나쁜 지점을 뜻하며, 오르내리면 하나의
	 * Incident 안에서 값이 진동해 history를 읽을 수 없게 된다. 낮은 값을 넘기는 것은 호출자가 무언가
	 * 잘못 알고 있다는 뜻이므로 조용히 넘기지 않는다.
	 */
	public void escalate(IncidentSeverity observed, Instant now) {
		if (this.status == IncidentStatus.RESOLVED) {
			throw new IllegalStateException("해결된 Incident의 심각도는 바꾸지 않는다: status=" + this.status);
		}
		if (!observed.isHigherThan(this.severity)) {
			throw new IllegalStateException("심각도는 올릴 수만 있다: severity=" + this.severity + " observed=" + observed);
		}
		this.severity = observed;
		this.updatedAt = now;
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

	public IncidentSeverity getSeverity() {
		return this.severity;
	}

	public IncidentStatus getStatus() {
		return this.status;
	}

	public Instant getOpenedAt() {
		return this.openedAt;
	}

	public Instant getAcknowledgedAt() {
		return this.acknowledgedAt;
	}

	public Instant getResolvedAt() {
		return this.resolvedAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public Instant getTriggerWindowStart() {
		return this.triggerWindowStart;
	}

	public Instant getTriggerWindowEnd() {
		return this.triggerWindowEnd;
	}

	public long getTriggerRequestCount() {
		return this.triggerRequestCount;
	}

	public long getTriggerErrorCount() {
		return this.triggerErrorCount;
	}

	public BigDecimal getTriggerErrorRate() {
		return this.triggerErrorRate;
	}

	public Long getTriggerP95LatencyMs() {
		return this.triggerP95LatencyMs;
	}

}

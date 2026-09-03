package com.pulseops.telemetry;

import com.pulseops.incident.IncidentDetector;
import com.pulseops.metric.MetricRecordOutcome;
import com.pulseops.metric.MetricRecorder;
import com.pulseops.metric.MetricSeries;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * 계약을 지킨 Telemetry Event 하나를 집계에 반영하고, 반영된 경우에만 이상 여부를 판정하게 한다.
 * <p>
 * 이 순서를 아는 곳을 하나로 모은다. {@link TelemetryEventListener}가 두 협력자를 직접 들고 순서를
 * 정하면, Kafka Record를 꺼내는 일과 관측 결과로 무엇을 할지 정하는 일이 한 Class에 섞인다. 그러면
 * Kafka 밖에서 같은 처리를 부를 자리가 생겼을 때 그 순서가 통째로 복사된다.
 * <p>
 * 판정을 부르는 조건은 "이 Event가 어떤 창에 속하는가" 하나다. 집계 창 밖의 Event만 판정을 부르지
 * 않는다. 그 Event는 어떤 창에도 들어가지 않아 이어서 확인할 창 자체가 없다.
 * <p>
 * 재전달된 Event로도 판정을 부른다. 이것이 Phase 7에서 달라진 점이다. 앞선 시도가 Redis 반영까지
 * 끝낸 뒤 판정 도중에 실패했다면, 재시도로 다시 온 그 Event는 Redis에서 중복으로 걸린다. 그때
 * "달라진 것이 없으니 할 일도 없다"고 끝내면 앞선 시도가 남긴 미완의 판정을 아무도 이어받지 못한다.
 * 트래픽이 계속되는 계열이라면 다음 Event가 같은 창을 다시 판정해 주지만, 그 Event가 계열의 마지막
 * Event였다면 그 창은 영영 판정되지 않는다.
 * <p>
 * 중복으로 판정을 한 번 더 부르는 것은 안전하다. 같은 창을 두 번 세지 않는 것은 Detection이 가진 DB
 * cursor가 보장하므로, 이미 판정된 창이면 아무것도 진행시키지 않고 끝난다.
 * <p>
 * 판정 기준 시각은 Metric 반영이 끝난 뒤 한 번만 읽는다. 한 Event를 처리하는 동안 기준이 흔들리면
 * 어느 창을 평가했는지가 호출마다 달라진다. Clock을 Bean으로 두지 않은 이유는 {@link MetricRecorder}
 * 와 같다.
 * <p>
 * 예외를 잡지 않는다. Phase 7부터 Redis와 MySQL 장애는 각 계층이 흡수하지 않고 그대로 올라오며, 그
 * 실패는 Kafka Consumer의 제한된 재시도와 DLT가 받는다. 여기에 catch를 하나 두면 재시도로 살릴 수
 * 있었던 Event가 성공으로 처리되어 사라진다.
 */
@Service
public class TelemetryProcessingService {

	private final MetricRecorder metricRecorder;

	private final IncidentDetector incidentDetector;

	public TelemetryProcessingService(MetricRecorder metricRecorder, IncidentDetector incidentDetector) {
		this.metricRecorder = metricRecorder;
		this.incidentDetector = incidentDetector;
	}

	public void process(TelemetryEvent event) {
		if (this.metricRecorder.record(event) == MetricRecordOutcome.OUT_OF_WINDOW) {
			return;
		}
		this.incidentDetector.detect(MetricSeries.from(event), Instant.now());
	}

}

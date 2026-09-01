package com.pulseops.telemetry;

import com.pulseops.incident.IncidentDetector;
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
 * 판정을 부르는 조건은 "Metric이 실제로 달라졌는가" 하나다. 재전달된 Event, 집계 창 밖의 Event,
 * Redis에 쓰지 못한 Event는 모두 Metric 상태를 바꾸지 않았으므로, 그것으로 판정을 부르면 직전과
 * 똑같은 창을 다시 읽고 다시 잠그는 일만 생긴다. 같은 창을 두 번 세지 않는 것은 Detection이 가진
 * DB cursor가 이미 보장하지만, 그 방어에 기대어 바뀌지도 않은 상태를 계속 되묻지는 않는다.
 * <p>
 * 판정 기준 시각은 Metric 반영이 끝난 뒤 한 번만 읽는다. 한 Event를 처리하는 동안 기준이 흔들리면
 * 어느 창을 평가했는지가 호출마다 달라진다. Clock을 Bean으로 두지 않은 이유는 {@link MetricRecorder}
 * 와 같다.
 * <p>
 * 예외를 잡지 않는다. Redis 장애는 {@link MetricRecorder}가, Redis와 MySQL 장애는
 * {@link IncidentDetector}가 각자 자기 자리에서 흡수한 뒤 이 자리까지 오지 않는다. 여기에 catch를
 * 하나 더 두면 두 계층이 일부러 올려 보낸 결함까지 함께 묻힌다.
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
		if (!this.metricRecorder.record(event)) {
			return;
		}
		this.incidentDetector.detect(MetricSeries.from(event), Instant.now());
	}

}

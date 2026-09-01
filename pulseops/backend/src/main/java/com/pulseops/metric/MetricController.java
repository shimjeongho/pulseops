package com.pulseops.metric;

import com.pulseops.metric.dto.MetricListResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metrics")
public class MetricController {

	private final MetricQueryService metricQueryService;

	public MetricController(MetricQueryService metricQueryService) {
		this.metricQueryService = metricQueryService;
	}

	@GetMapping
	public MetricListResponse getMetrics() {
		return this.metricQueryService.currentMetrics();
	}

}

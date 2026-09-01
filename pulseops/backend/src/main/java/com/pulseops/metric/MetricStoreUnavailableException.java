package com.pulseops.metric;

/**
 * Metric을 읽어야 하는 요청에서 Redis에 닿지 못했음을 뜻한다.
 * <p>
 * Redis가 던지는 {@code DataAccessException}을 그대로 Web 계층까지 올려 보내지 않는다. 그 Type은
 * Redis와 MySQL이 함께 쓰는 것이라, Web 계층에서 그것 하나만 보고 503으로 옮기면 Incident를 읽다
 * 실패한 MySQL 오류까지 "Metric 저장소를 사용할 수 없다"는 잘못된 설명으로 나간다. 어디서 실패했는지
 * 아는 곳은 그 호출을 한 자리뿐이므로 번역도 그 자리에서 한다.
 * <p>
 * 실패의 자세한 내용은 cause에만 담고 API 응답에는 옮기지 않는다. Redis가 돌려준 message에는 Key나
 * 값이 들어 있을 수 있다.
 */
public class MetricStoreUnavailableException extends RuntimeException {

	public MetricStoreUnavailableException(Throwable cause) {
		super(cause);
	}

}

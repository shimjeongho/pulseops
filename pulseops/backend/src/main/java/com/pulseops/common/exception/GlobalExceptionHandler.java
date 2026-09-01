package com.pulseops.common.exception;

import com.pulseops.metric.MetricStoreUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 실패를 API 오류 응답으로 옮긴다. 그래서 Controller는 HTTP 상태를 정하기 위해 예외를 직접 다루지
 * 않는다.
 * <p>
 * Error Code는 여기서만 정한다. 같은 실패가 자리마다 다른 code로 나가면 Client가 code 대신 status로
 * 분기하게 되고, 그러면 code가 있을 이유가 없어진다.
 * <p>
 * {@code DataAccessException}을 직접 잡지 않는다. Redis와 MySQL이 함께 쓰는 Type이라 여기서 하나로
 * 옮기면 어느 저장소가 아픈지 모르는 채로 설명을 붙이게 된다. 어디서 실패했는지 아는 것은 그 호출을
 * 한 계층이므로 번역도 그쪽에서 끝내고, 이 Class는 이미 뜻이 정해진 예외만 받는다.
 * <p>
 * 적용 대상을 PulseOps의 Controller로 한정한다. 아래 catch-all은 뜻을 정해 두지 않은 실패를 500으로
 * 덮는데, 범위를 열어 두면 Spring MVC가 Controller에 닿기도 전에 정한 상태까지 함께 덮는다. 실제로
 * 없는 경로는 404가 아니라 500이 되고, 허용되지 않는 method도 405가 아니라 500이 된다. 그러면 Client는
 * 자기 요청의 잘못을 서버 장애로 읽고 운영자는 있지도 않은 장애를 쫓는다.
 * <p>
 * Controller 목록을 나열하지 않고 package로 정한다. 나열하면 Controller가 늘 때마다 여기에 더해야
 * 하고, 빠뜨린 Controller는 조용히 다른 모양의 응답을 내보낸다.
 */
@RestControllerAdvice(basePackages = "com.pulseops")
public class GlobalExceptionHandler {

	private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/**
	 * Metric은 Redis에만 있으므로 읽지 못하면 돌려줄 값이 없다. 빈 목록으로 바꾸면 화면이 "관측 중인
	 * API 없음"을 보여 주게 되어, 장애가 정상으로 보인다.
	 * <p>
	 * log에는 원인 예외의 Type 이름만 남긴다. Redis가 돌려준 message에는 Key나 값이 담겨 올 수 있다.
	 */
	@ExceptionHandler(MetricStoreUnavailableException.class)
	public ResponseEntity<ErrorResponse> handleMetricStoreUnavailable(MetricStoreUnavailableException exception,
			HttpServletRequest request) {
		Throwable cause = exception.getCause();
		logger.warn("Metric 저장소를 읽지 못해 요청을 처리하지 못했다. path={} reason={}", request.getRequestURI(),
				(cause != null) ? cause.getClass().getSimpleName() : "unknown");
		return build(HttpStatus.SERVICE_UNAVAILABLE, "METRIC_STORE_UNAVAILABLE", "Metric 저장소를 사용할 수 없습니다.",
				request);
	}

	/**
	 * 뜻을 정해 두지 않은 실패이다. Client에게는 고정된 문장만 보내고, 무엇이 잘못됐는지는 log에만
	 * 남긴다. 예외의 message에는 Key나 SQL, 내부 경로가 그대로 담겨 올 수 있다.
	 * <p>
	 * Spring MVC가 스스로 상태를 정하는 실패는 이 Class의 적용 범위 밖이라 여기로 오지 않는다. 그
	 * 경계는 {@code GlobalExceptionHandlerIntegrationTest}가 고정한다.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
		logger.error("처리하지 못한 오류로 요청에 실패했다. path={}", request.getRequestURI(), exception);
		return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "요청을 처리하는 중 오류가 발생했습니다.", request);
	}

	private static ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
			HttpServletRequest request) {
		return ResponseEntity.status(status)
			.body(ErrorResponse.of(status.value(), code, message, request.getRequestURI()));
	}

}

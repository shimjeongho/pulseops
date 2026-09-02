package com.pulseops.common.exception;

import com.pulseops.incident.IncidentNotFoundException;
import com.pulseops.metric.MetricStoreUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
 * 다만 Spring MVC가 정하는 실패라고 해서 전부 범위 밖인 것은 아니다. 없는 경로나 허용되지 않는
 * method는 어느 Controller가 처리할지 정해지기 전에 거절되어 이 Class에 닿지 않지만, Path Variable
 * 변환 실패는 Controller가 이미 정해진 뒤 그 인자를 만들다 일어나므로 여기로 온다. 그대로 두면
 * 아래 catch-all이 Client의 잘못된 요청을 서버 장애로 바꾸므로 그 하나는 직접 받는다.
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
	 * 요청이 가리킨 Incident가 없다. 서버가 실패한 것이 아니라 Client가 없는 것을 가리킨 것이므로
	 * 404이며, log를 남기지 않는다. 없는 ID를 물어보는 것은 정상적인 요청의 결과이고, 그것을 경고로
	 * 남기면 실제 장애를 찾을 때 걸러 내야 할 줄만 늘어난다.
	 * <p>
	 * message에 ID를 넣지 않는다. 무엇을 찾지 못했는지는 같은 응답의 path에 이미 있고, 두 자리에
	 * 적으면 한쪽만 바뀌었을 때 서로 다른 것을 가리킨다.
	 */
	@ExceptionHandler(IncidentNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleIncidentNotFound(IncidentNotFoundException exception,
			HttpServletRequest request) {
		return build(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "Incident를 찾을 수 없습니다.", request);
	}

	/**
	 * Path Variable을 선언된 Type으로 바꾸지 못했다. 요청 자체가 성립하지 않으므로 400이다.
	 * <p>
	 * 잡는 Type을 이 하나로 좁힌다. {@code IllegalArgumentException}까지 넓히면 Service 안에서 잘못된
	 * 인자로 실패한 서버의 결함이 Client의 잘못으로 보고되고, 500이어야 할 실패가 400으로 나가 아무도
	 * 그것을 장애로 알아채지 못한다.
	 * <p>
	 * 어느 인자가 왜 실패했는지는 응답에 담지 않는다. 이 예외의 message에는 Controller의 method
	 * 이름과 인자 Type이 그대로 들어 있어, 그것을 내보내면 내부 구조가 API 응답이 된다.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
			HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.", request);
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

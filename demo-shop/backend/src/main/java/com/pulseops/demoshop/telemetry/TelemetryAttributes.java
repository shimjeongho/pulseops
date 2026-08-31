package com.pulseops.demoshop.telemetry;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 하나의 Request를 처리하는 여러 계층이 Telemetry 정보를 주고받는 Request Attribute이다.
 * <p>
 * RequestIdFilter, GlobalExceptionHandler, TelemetryFilter는 서로를 직접 호출하지 않으므로
 * Servlet Request를 전달 통로로 사용한다. Attribute 이름과 형 변환을 이 한 곳에만 두어 이름이
 * 어긋나 값이 조용히 사라지는 일을 막는다.
 */
public final class TelemetryAttributes {

	private static final String REQUEST_ID = TelemetryAttributes.class.getName() + ".requestId";

	private static final String ERROR_TYPE = TelemetryAttributes.class.getName() + ".errorType";

	private static final String ERROR_MESSAGE = TelemetryAttributes.class.getName() + ".errorMessage";

	private TelemetryAttributes() {
	}

	public static void setRequestId(HttpServletRequest request, String requestId) {
		request.setAttribute(REQUEST_ID, requestId);
	}

	public static String getRequestId(HttpServletRequest request) {
		return (String) request.getAttribute(REQUEST_ID);
	}

	/**
	 * 이미 확정된 Error Code와 Client에게 실제로 반환한 message를 남긴다. Telemetry가 Exception을
	 * 다시 해석해 Error Code를 중복 결정하지 않도록, 오류 분류의 소유자는 GlobalExceptionHandler
	 * 하나로 유지한다.
	 */
	public static void setError(HttpServletRequest request, String errorType, String errorMessage) {
		request.setAttribute(ERROR_TYPE, errorType);
		request.setAttribute(ERROR_MESSAGE, errorMessage);
	}

	public static String getErrorType(HttpServletRequest request) {
		return (String) request.getAttribute(ERROR_TYPE);
	}

	public static String getErrorMessage(HttpServletRequest request) {
		return (String) request.getAttribute(ERROR_MESSAGE);
	}

}

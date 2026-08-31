package com.pulseops.demoshop.telemetry;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 Request에 하나의 식별자를 부여한다. Client가 보낸 값이 있으면 이어받아 호출 구간을
 * 연결하고, 없거나 신뢰할 수 없으면 새로 만든다. 확정한 값은 Request Attribute와 Response
 * Header 양쪽에 실어 Client와 Telemetry가 같은 값을 보게 한다.
 * <p>
 * 이 Filter는 Request Identity만 책임진다. 소요 시간 측정, Event 생성, 오류 분류는 모두
 * TelemetryFilter의 몫이다.
 */
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String REQUEST_ID_HEADER = "X-Request-Id";

	/**
	 * 외부에서 들어온 값을 그대로 Response Header와 Event에 싣기 때문에 형식을 제한한다. 공백,
	 * 개행, Control Character를 허용하면 Header와 log가 오염되고, 길이를 제한하지 않으면 뒤에서
	 * 다루기 어려운 값이 그대로 흘러 들어간다.
	 */
	private static final Pattern ALLOWED_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
		TelemetryAttributes.setRequestId(request, requestId);
		response.setHeader(REQUEST_ID_HEADER, requestId);
		chain.doFilter(request, response);
	}

	private String resolveRequestId(String headerValue) {
		if (headerValue != null && ALLOWED_REQUEST_ID.matcher(headerValue).matches()) {
			return headerValue;
		}
		return UUID.randomUUID().toString();
	}

}

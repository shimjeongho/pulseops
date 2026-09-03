package com.pulseops.demoshop.telemetry;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * API Request가 어떻게 끝났는지를 공통 계층에서 관측하여 Telemetry Event로 만든다. Controller나
 * Service에 관측 코드를 넣지 않기 위해 Filter 한 곳에서만 처리한다.
 * <p>
 * 관측은 Business 처리에 개입하지 않는다. Business chain에서 올라온 Throwable은 반드시 그대로
 * 다시 던지고, 반대로 관측 자체가 실패해도 Business 응답을 바꾸지 않는다.
 */
public class TelemetryFilter extends OncePerRequestFilter {

	/**
	 * 관측 대상 경로이자 Route Template으로 인정하는 접두사이다. Static Resource, 오류 페이지,
	 * Frontend 자원은 Demo Shop의 API 상태와 무관하므로 관측하지 않는다.
	 */
	private static final String API_PATH_PREFIX = "/api/";

	private static final String SERVICE_NAME = "demo-shop";

	private static final int SCHEMA_VERSION = 1;

	/** Handler가 매칭되지 않아 Route Template을 알 수 없을 때 사용하는 고정 값이다. */
	private static final String UNMAPPED_ENDPOINT = "UNMAPPED";

	private static final String INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR";

	/** GlobalExceptionHandler가 다루지 않은 4xx이다. 404 unmapped, 405 등이 여기에 속한다. */
	private static final String UNHANDLED_CLIENT_ERROR = "UNHANDLED_CLIENT_ERROR";

	private final TelemetryPublisher publisher;

	private final TelemetryProperties properties;

	public TelemetryFilter(TelemetryPublisher publisher, TelemetryProperties properties) {
		this.publisher = publisher;
		this.properties = properties;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith(API_PATH_PREFIX);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long startNanos = System.nanoTime();
		Throwable failure = null;
		try {
			chain.doFilter(request, response);
		}
		catch (Throwable ex) {
			failure = ex;
			throw ex;
		}
		finally {
			// 측정을 publish보다 먼저 끝낸다. Telemetry 전송 비용이 관측 대상 API의 소요 시간으로
			// 잘못 기록되면 Metric이 실제 사용자 경험과 달라진다.
			long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
			observe(request, response, failure, latencyMs);
		}
	}

	/**
	 * Event 생성과 발행에서 생긴 오류는 여기서 끝낸다. 관측 실패 때문에 이미 처리된 Business
	 * 응답이 달라지면 안 되기 때문이다. Business chain에서 올라온 Throwable은 이 Method가 아니라
	 * 호출부에서 이미 다시 던지고 있다.
	 */
	private void observe(HttpServletRequest request, HttpServletResponse response, Throwable failure,
			long latencyMs) {
		try {
			this.publisher.publish(toEvent(request, response, failure, latencyMs));
		}
		catch (RuntimeException ex) {
			this.logger.warn("Telemetry Event를 발행하지 못했다.", ex);
		}
	}

	private TelemetryEvent toEvent(HttpServletRequest request, HttpServletResponse response, Throwable failure,
			long latencyMs) {
		int httpStatus = resolveHttpStatus(response, failure);
		return new TelemetryEvent(UUID.randomUUID().toString(), SCHEMA_VERSION, Instant.now(), SERVICE_NAME,
				this.properties.serviceInstance(), TelemetryAttributes.getRequestId(request), request.getMethod(),
				resolveEndpoint(request), httpStatus, httpStatus < HttpStatus.BAD_REQUEST.value(), latencyMs,
				resolveErrorType(request, failure, httpStatus), resolveErrorMessage(request, failure, httpStatus));
	}

	/**
	 * Exception이 chain 밖으로 올라온 경우 Response는 아직 최종 Status를 갖고 있지 않다. 이때
	 * Client가 결국 받게 되는 500을 기록한다.
	 */
	private int resolveHttpStatus(HttpServletResponse response, Throwable failure) {
		return (failure != null) ? HttpStatus.INTERNAL_SERVER_ERROR.value() : response.getStatus();
	}

	/**
	 * Route Template은 Handler Mapping이 결정한 pattern에서 가져온다. 매칭이 없거나 Static
	 * Resource의 catch-all pattern이 걸린 경우처럼 API Route가 아닌 결과는 UNMAPPED로 기록한다.
	 * 실제 URI를 대신 쓰면 ID마다 endpoint가 갈라져 집계가 무너지고 URI에 담긴 값이 그대로
	 * 밖으로 나간다.
	 */
	private String resolveEndpoint(HttpServletRequest request) {
		Object bestMatchingPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		if (bestMatchingPattern == null) {
			return UNMAPPED_ENDPOINT;
		}
		String routeTemplate = bestMatchingPattern.toString();
		return routeTemplate.startsWith(API_PATH_PREFIX) ? routeTemplate : UNMAPPED_ENDPOINT;
	}

	/**
	 * GlobalExceptionHandler가 Error Code를 확정한 오류는 5xx라도 그 Code를 그대로 남긴다. 이미
	 * Client에게 안정된 Code로 응답한 오류를 관측만 INTERNAL_SERVER_ERROR로 뭉개면, 원인이 분명한
	 * 실패와 원인을 모르는 실패가 같은 이름으로 섞여 Incident에서 구분할 수 없게 된다.
	 * <p>
	 * Code가 없는 5xx는 여전히 처리되지 않은 오류이므로 INTERNAL_SERVER_ERROR로 남긴다. Exception이
	 * chain 밖으로 올라온 경우에는 응답 본문이 확정되지 않았으므로 Attribute를 보지 않는다.
	 */
	private String resolveErrorType(HttpServletRequest request, Throwable failure, int httpStatus) {
		if (failure != null) {
			return INTERNAL_SERVER_ERROR;
		}
		if (httpStatus < HttpStatus.BAD_REQUEST.value()) {
			return null;
		}
		String handledErrorCode = TelemetryAttributes.getErrorType(request);
		if (handledErrorCode != null) {
			return handledErrorCode;
		}
		return (httpStatus >= HttpStatus.INTERNAL_SERVER_ERROR.value()) ? INTERNAL_SERVER_ERROR
				: UNHANDLED_CLIENT_ERROR;
	}

	/**
	 * Client에게 실제로 반환한 message만 싣는다. 처리되지 않은 Exception의 message는 내부 구현을
	 * 그대로 드러낼 수 있으므로 남기지 않는다.
	 */
	private String resolveErrorMessage(HttpServletRequest request, Throwable failure, int httpStatus) {
		if (failure != null || httpStatus < HttpStatus.BAD_REQUEST.value()) {
			return null;
		}
		return TelemetryAttributes.getErrorMessage(request);
	}

}

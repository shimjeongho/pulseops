package com.pulseops.demoshop.common.exception;

import java.time.Instant;
import java.util.List;

/**
 * Common API error body. {@code fieldErrors} is always present so clients can rely on
 * the shape; it is empty for errors that are not about a specific input field.
 */
public record ErrorResponse(Instant timestamp, int status, String code, String message, String path,
		List<String> fieldErrors) {

	public static ErrorResponse of(int status, String code, String message, String path) {
		return of(status, code, message, path, List.of());
	}

	public static ErrorResponse of(int status, String code, String message, String path, List<String> fieldErrors) {
		return new ErrorResponse(Instant.now(), status, code, message, path, List.copyOf(fieldErrors));
	}

}

package com.pulseops.demoshop.common.exception;

import java.time.Instant;
import java.util.List;

/**
 * 공통 API 오류 응답 본문이다. 클라이언트가 응답 형태를 일관되게 다룰 수 있도록
 * {@code fieldErrors}는 항상 존재하며, 특정 입력 필드와 무관한 오류에서는 빈 목록이다.
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

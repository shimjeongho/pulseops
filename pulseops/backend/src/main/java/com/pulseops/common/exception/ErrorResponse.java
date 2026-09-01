package com.pulseops.common.exception;

import java.time.Instant;

/**
 * PulseOps API가 실패를 알리는 유일한 본문 형태이다.
 * <p>
 * code는 Client가 분기에 쓰는 API 계약이므로 Java Exception Class 이름을 그대로 내보내지 않는다.
 * 그 이름은 언제든 리팩터링으로 바뀌는 내부 사정이고, 한 번 나가면 Client가 그것에 묶인다.
 * <p>
 * message는 사람이 읽을 고정 문장이며 Exception의 message를 옮겨 담지 않는다. Redis Key, SQL,
 * Kafka payload처럼 밖으로 나가면 안 되는 값이 그 자리에 담겨 오기 때문이다. 무엇이 잘못됐는지
 * 자세히 남길 곳은 응답이 아니라 서버 log이다.
 * <p>
 * Demo Shop의 같은 이름 record와 달리 fieldErrors를 두지 않는다. PulseOps API에는 검증할 Request
 * Body가 없다. 지금 쓰이지 않는 자리를 만들어 두면 모든 응답이 빈 목록을 달고 다닌다.
 * <p>
 * timestamp는 응답을 만든 시각이며 오류가 난 시각과 같다고 보아도 되는 거리이다. Instant를 쓰므로
 * JSON에는 UTC 기준 ISO-8601 문자열로 나가고, Client는 자기 시간대로 바꿔 보여 준다. 서버가 특정
 * 지역 표기로 미리 바꾸면 그 판단을 Client가 되돌릴 수 없다.
 */
public record ErrorResponse(Instant timestamp, int status, String code, String message, String path) {

	public static ErrorResponse of(int status, String code, String message, String path) {
		return new ErrorResponse(Instant.now(), status, code, message, path);
	}

}

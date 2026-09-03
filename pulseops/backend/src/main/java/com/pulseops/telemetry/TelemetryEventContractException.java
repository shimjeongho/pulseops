package com.pulseops.telemetry;

/**
 * 수신한 Record가 Telemetry Event v1 계약을 지키지 않았음을 나타낸다.
 * <p>
 * 재시도해도 결과가 달라지지 않는 실패다. 같은 payload를 몇 번 다시 읽어도 빠진 필드가 생기거나
 * schemaVersion이 바뀌지 않으므로, Consumer 설정에서 재시도 대상이 아닌 예외로 등록해 곧바로 DLT로
 * 보낸다.
 * <p>
 * message에는 어떤 규칙이 깨졌는지만 담고 payload와 필드 값은 담지 않는다. 이 message는 DLT Record의
 * header로도 나가므로, 값을 담으면 Client에게 나갔던 문장이나 문제가 된 값이 그대로 다른 Topic에
 * 복사된다.
 */
public class TelemetryEventContractException extends RuntimeException {

	public TelemetryEventContractException(String violation) {
		super(violation);
	}

}

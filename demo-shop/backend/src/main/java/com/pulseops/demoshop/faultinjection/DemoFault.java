package com.pulseops.demoshop.faultinjection;

/**
 * Demo에서 재현할 수 있는 장애 유형이다. 요청 Header로 하나를 지정하면 그 요청 하나만 해당
 * 방식으로 끝난다.
 * <p>
 * 값을 고정 목록으로 둔다. 지연 시간이나 Status Code를 요청이 직접 정하게 하면 Demo용 기능이
 * 임의의 값을 받는 통로가 되고, 어떤 값이 들어올지 알 수 없으니 재현도 어려워진다.
 */
public enum DemoFault {

	/** 요청을 Business 처리 전에 503으로 끝낸다. */
	ERROR_503,

	/** Business 처리 전에 고정 시간만큼 지연시킨 뒤 정상 응답을 반환한다. */
	LATENCY_3500

}

package com.pulseops.demoshop.payment;

/**
 * 결제 시도의 결과이다. 결제 기록은 결과가 확정된 뒤에만 저장되므로 진행 중 상태는 두지 않는다.
 */
public enum PaymentStatus {

	SUCCESS,
	FAILED

}

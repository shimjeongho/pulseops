package com.pulseops.demoshop.payment;

/**
 * 결제 시도의 결과이다. 결제 기록은 결과가 확정된 뒤에만 저장되므로 진행 중 상태는 두지 않는다.
 * <p>
 * 현재 결제 Flow는 실패 시 Transaction이 rollback되어 Payment Row 자체가 남지 않으므로 실제로
 * 저장되는 값은 {@code SUCCESS}뿐이다. {@code FAILED}와 Payment의 failureCode / failureMessage는
 * 실패한 시도까지 기록하는 정책이 필요해질 때 사용한다.
 */
public enum PaymentStatus {

	SUCCESS,
	FAILED

}

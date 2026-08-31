package com.pulseops.demoshop.order;

/**
 * 결제 대기 상태가 아닌 주문에 결제를 시도한 경우 발생한다. 중복 결제를 조용히 무시하지 않고
 * 거부하여, 두 번째 시도가 아무것도 변경하지 않았음을 호출자가 알 수 있게 한다.
 */
public class OrderAlreadyPaidException extends RuntimeException {

	private final Long orderId;

	public OrderAlreadyPaidException(Long orderId) {
		super("Order is already paid: " + orderId);
		this.orderId = orderId;
	}

	public Long getOrderId() {
		return this.orderId;
	}

}

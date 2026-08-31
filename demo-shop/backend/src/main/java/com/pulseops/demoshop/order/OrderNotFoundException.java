package com.pulseops.demoshop.order;

/**
 * 요청한 ID에 해당하는 주문이 없을 때 발생한다.
 */
public class OrderNotFoundException extends RuntimeException {

	private final Long orderId;

	public OrderNotFoundException(Long orderId) {
		super("Order not found: " + orderId);
		this.orderId = orderId;
	}

	public Long getOrderId() {
		return this.orderId;
	}

}

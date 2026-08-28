package com.pulseops.demoshop.order;

/**
 * Raised when payment is attempted for an order that is no longer awaiting payment.
 * Paying twice for one order is rejected rather than made idempotent, so the caller
 * knows the second attempt changed nothing.
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

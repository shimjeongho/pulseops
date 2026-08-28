package com.pulseops.demoshop.order;

/**
 * Raised when an order lookup finds no order for the requested id.
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

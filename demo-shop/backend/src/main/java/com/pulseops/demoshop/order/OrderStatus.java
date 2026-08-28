package com.pulseops.demoshop.order;

/**
 * Lifecycle status of an order. Orders are created as {@code PENDING_PAYMENT};
 * the transition to {@code PAID} is the responsibility of payment handling.
 */
public enum OrderStatus {

	PENDING_PAYMENT,
	PAID

}

package com.pulseops.demoshop.order;

/**
 * 주문의 생명주기 상태이다. 주문은 {@code PENDING_PAYMENT} 상태로 생성되며
 * {@code PAID}로의 전이는 결제 처리의 책임이다.
 */
public enum OrderStatus {

	PENDING_PAYMENT,
	PAID

}

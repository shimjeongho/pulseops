package com.pulseops.demoshop.product;

/**
 * 상품의 판매 상태이다. 품절은 별도 상태로 두지 않고 {@code stockQuantity == 0}으로 판단한다.
 */
public enum ProductStatus {

	ACTIVE,
	INACTIVE

}

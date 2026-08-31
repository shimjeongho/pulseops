package com.pulseops.demoshop.product;

/**
 * 판매 중인 상품이 필요한 곳에 판매 중이 아닌 상품을 사용한 경우 발생한다.
 */
public class ProductInactiveException extends RuntimeException {

	private final Long productId;

	public ProductInactiveException(Long productId) {
		super("Product is not active: " + productId);
		this.productId = productId;
	}

	public Long getProductId() {
		return this.productId;
	}

}

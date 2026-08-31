package com.pulseops.demoshop.product;

/**
 * 요청한 ID에 해당하는 상품이 없을 때 발생한다.
 */
public class ProductNotFoundException extends RuntimeException {

	private final Long productId;

	public ProductNotFoundException(Long productId) {
		super("Product not found: " + productId);
		this.productId = productId;
	}

	public Long getProductId() {
		return this.productId;
	}

}

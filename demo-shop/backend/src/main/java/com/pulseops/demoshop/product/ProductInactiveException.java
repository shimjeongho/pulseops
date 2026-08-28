package com.pulseops.demoshop.product;

/**
 * Raised when a product that is not on sale is used where an active product is
 * required.
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

package com.pulseops.demoshop.product;

/**
 * Raised when a product lookup finds no product for the requested id.
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

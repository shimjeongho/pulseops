package com.pulseops.demoshop.order;

/**
 * Raised when one order request lists the same product more than once. Such requests
 * are rejected rather than merged, so the caller resolves the ambiguity.
 */
public class DuplicateProductException extends RuntimeException {

	private final Long productId;

	public DuplicateProductException(Long productId) {
		super("Duplicate product in order request: " + productId);
		this.productId = productId;
	}

	public Long getProductId() {
		return this.productId;
	}

}

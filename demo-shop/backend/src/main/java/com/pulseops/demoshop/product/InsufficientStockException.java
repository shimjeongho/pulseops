package com.pulseops.demoshop.product;

/**
 * Raised when a product cannot cover the quantity a payment is trying to take off the
 * shelf. The product is identified so the caller can be told which line is short.
 */
public class InsufficientStockException extends RuntimeException {

	private final Long productId;

	private final String productName;

	private final int requestedQuantity;

	private final int availableQuantity;

	public InsufficientStockException(Long productId, String productName, int requestedQuantity,
			int availableQuantity) {
		super("Insufficient stock for product " + productId + ": requested " + requestedQuantity + ", available "
				+ availableQuantity);
		this.productId = productId;
		this.productName = productName;
		this.requestedQuantity = requestedQuantity;
		this.availableQuantity = availableQuantity;
	}

	public Long getProductId() {
		return this.productId;
	}

	public String getProductName() {
		return this.productName;
	}

	public int getRequestedQuantity() {
		return this.requestedQuantity;
	}

	public int getAvailableQuantity() {
		return this.availableQuantity;
	}

}

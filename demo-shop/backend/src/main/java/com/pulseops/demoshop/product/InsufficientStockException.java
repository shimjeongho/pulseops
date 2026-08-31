package com.pulseops.demoshop.product;

/**
 * 결제가 차감하려는 수량을 상품 재고가 감당하지 못할 때 발생한다. 어떤 상품이 부족한지
 * 호출자에게 알릴 수 있도록 상품 정보를 함께 담는다.
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

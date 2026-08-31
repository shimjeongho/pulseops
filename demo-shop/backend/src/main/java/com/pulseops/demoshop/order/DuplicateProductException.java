package com.pulseops.demoshop.order;

/**
 * 하나의 주문 요청에 같은 상품이 두 번 이상 포함된 경우 발생한다. 수량을 임의로 합치지 않고
 * 요청을 거부하여 호출자가 의도를 명확히 하도록 한다.
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

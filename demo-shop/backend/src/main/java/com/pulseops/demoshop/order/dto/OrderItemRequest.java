package com.pulseops.demoshop.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 주문 요청의 단일 항목이다. 가격 필드는 의도적으로 두지 않으며, 서버가 자신의 상품 데이터로
 * 주문 금액을 계산한다.
 */
public record OrderItemRequest(@NotNull Long productId, @NotNull @Min(1) Integer quantity) {

}

package com.pulseops.demoshop.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 주문 생성 요청 본문이다. {@code @Valid}가 각 항목까지 Validation을 전파한다.
 */
public record OrderCreateRequest(@NotEmpty @Valid List<OrderItemRequest> items) {

}

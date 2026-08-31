package com.pulseops.demoshop.payment.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 결제 요청 본문이다. 주문만 지정하며, 결제 금액과 결과는 호출자가 아니라 서버가 주문 정보로
 * 결정한다.
 */
public record PaymentCreateRequest(@NotNull Long orderId) {

}

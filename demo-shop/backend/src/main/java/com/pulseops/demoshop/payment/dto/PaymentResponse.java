package com.pulseops.demoshop.payment.dto;

import com.pulseops.demoshop.payment.Payment;
import com.pulseops.demoshop.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentResponse(Long id, String paymentNumber, Long orderId, BigDecimal amount, PaymentStatus status,
		LocalDateTime createdAt) {

	public static PaymentResponse from(Payment payment) {
		return new PaymentResponse(payment.getId(), payment.getPaymentNumber(), payment.getOrder().getId(),
				payment.getAmount(), payment.getStatus(), payment.getCreatedAt());
	}

}

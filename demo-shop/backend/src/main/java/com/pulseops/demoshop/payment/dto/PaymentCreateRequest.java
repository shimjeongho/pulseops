package com.pulseops.demoshop.payment.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body of a payment request. Only the order is named: the amount and the outcome are
 * decided by the server from the order itself, never by the caller.
 */
public record PaymentCreateRequest(@NotNull Long orderId) {

}

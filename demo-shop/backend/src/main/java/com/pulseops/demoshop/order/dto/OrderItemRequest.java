package com.pulseops.demoshop.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * One requested line of an order. Price is deliberately absent: the server prices the
 * order from its own product data.
 */
public record OrderItemRequest(@NotNull Long productId, @NotNull @Min(1) Integer quantity) {

}

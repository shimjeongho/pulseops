package com.pulseops.demoshop.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Body of a create-order request. {@code @Valid} cascades validation into each item.
 */
public record OrderCreateRequest(@NotEmpty @Valid List<OrderItemRequest> items) {

}

package com.pulseops.demoshop.order.dto;

import com.pulseops.demoshop.order.OrderItem;
import java.math.BigDecimal;

public record OrderItemResponse(Long productId, String productName, BigDecimal unitPrice, int quantity,
		BigDecimal subtotal) {

	public static OrderItemResponse from(OrderItem item) {
		return new OrderItemResponse(item.getProductId(), item.getProductName(), item.getUnitPrice(),
				item.getQuantity(), item.getSubtotal());
	}

}

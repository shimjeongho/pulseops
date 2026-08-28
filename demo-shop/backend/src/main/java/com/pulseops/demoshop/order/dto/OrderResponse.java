package com.pulseops.demoshop.order.dto;

import com.pulseops.demoshop.order.Order;
import com.pulseops.demoshop.order.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderResponse(Long id, String orderNumber, OrderStatus status, BigDecimal totalAmount,
		LocalDateTime createdAt, List<OrderItemResponse> items) {

	public static OrderResponse from(Order order) {
		return new OrderResponse(order.getId(), order.getOrderNumber(), order.getStatus(), order.getTotalAmount(),
				order.getCreatedAt(), order.getItems().stream().map(OrderItemResponse::from).toList());
	}

}

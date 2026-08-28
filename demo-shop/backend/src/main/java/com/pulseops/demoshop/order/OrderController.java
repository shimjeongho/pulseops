package com.pulseops.demoshop.order;

import com.pulseops.demoshop.order.dto.OrderCreateRequest;
import com.pulseops.demoshop.order.dto.OrderResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public OrderResponse createOrder(@Valid @RequestBody OrderCreateRequest request) {
		return OrderResponse.from(this.orderService.create(request));
	}

	@GetMapping
	public List<OrderResponse> getOrders() {
		return this.orderService.findAll().stream().map(OrderResponse::from).toList();
	}

	@GetMapping("/{orderId}")
	public OrderResponse getOrder(@PathVariable Long orderId) {
		return OrderResponse.from(this.orderService.getById(orderId));
	}

}

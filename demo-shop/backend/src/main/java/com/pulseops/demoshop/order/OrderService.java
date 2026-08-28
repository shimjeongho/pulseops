package com.pulseops.demoshop.order;

import com.pulseops.demoshop.order.dto.OrderCreateRequest;
import com.pulseops.demoshop.order.dto.OrderItemRequest;
import com.pulseops.demoshop.product.Product;
import com.pulseops.demoshop.product.ProductInactiveException;
import com.pulseops.demoshop.product.ProductNotFoundException;
import com.pulseops.demoshop.product.ProductRepository;
import com.pulseops.demoshop.product.ProductStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderService {

	private static final String ORDER_NUMBER_PREFIX = "ORD-";

	private final OrderRepository orderRepository;

	private final ProductRepository productRepository;

	public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
		this.orderRepository = orderRepository;
		this.productRepository = productRepository;
	}

	/**
	 * Creates an order priced from the server's own product data. The order and all of
	 * its items are written in one transaction, so a failure part-way leaves nothing
	 * behind. Product stock is deliberately left untouched: reserving stock belongs to
	 * payment, not to placing the order.
	 */
	@Transactional
	public Order create(OrderCreateRequest request) {
		List<OrderItemRequest> requestedItems = request.items();
		rejectDuplicateProducts(requestedItems);

		Map<Long, Product> productsById = loadProductsById(requestedItems);

		List<OrderItem> items = new ArrayList<>();
		for (OrderItemRequest requestedItem : requestedItems) {
			Product product = productsById.get(requestedItem.productId());
			if (product == null) {
				throw new ProductNotFoundException(requestedItem.productId());
			}
			if (product.getStatus() != ProductStatus.ACTIVE) {
				throw new ProductInactiveException(product.getId());
			}
			items.add(new OrderItem(product.getId(), product.getName(), product.getPrice(),
					requestedItem.quantity()));
		}

		return this.orderRepository.save(new Order(generateOrderNumber(), items));
	}

	public List<Order> findAll() {
		return this.orderRepository.findAllWithItems();
	}

	public Order getById(Long orderId) {
		return this.orderRepository.findByIdWithItems(orderId)
			.orElseThrow(() -> new OrderNotFoundException(orderId));
	}

	private void rejectDuplicateProducts(List<OrderItemRequest> requestedItems) {
		Set<Long> seen = new HashSet<>();
		for (OrderItemRequest requestedItem : requestedItems) {
			if (!seen.add(requestedItem.productId())) {
				throw new DuplicateProductException(requestedItem.productId());
			}
		}
	}

	private Map<Long, Product> loadProductsById(List<OrderItemRequest> requestedItems) {
		List<Long> productIds = requestedItems.stream().map(OrderItemRequest::productId).toList();
		return this.productRepository.findAllById(productIds)
			.stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));
	}

	private String generateOrderNumber() {
		return ORDER_NUMBER_PREFIX + UUID.randomUUID();
	}

}

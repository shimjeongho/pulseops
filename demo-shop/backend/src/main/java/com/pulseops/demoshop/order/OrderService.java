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
	 * 서버가 보관한 상품 데이터를 기준으로 가격을 계산해 주문을 생성한다. 주문과 모든 항목은
	 * 하나의 Transaction에서 저장되므로 도중에 실패하면 아무것도 남지 않는다. 재고는 의도적으로
	 * 건드리지 않는다. 재고 확보는 주문이 아니라 결제의 책임이다.
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

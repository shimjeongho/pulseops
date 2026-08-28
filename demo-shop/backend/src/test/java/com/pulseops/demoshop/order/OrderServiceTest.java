package com.pulseops.demoshop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pulseops.demoshop.order.dto.OrderCreateRequest;
import com.pulseops.demoshop.order.dto.OrderItemRequest;
import com.pulseops.demoshop.product.Product;
import com.pulseops.demoshop.product.ProductInactiveException;
import com.pulseops.demoshop.product.ProductNotFoundException;
import com.pulseops.demoshop.product.ProductRepository;
import com.pulseops.demoshop.product.ProductStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OrderServiceTest {

	private OrderRepository orderRepository;

	private ProductRepository productRepository;

	private OrderService orderService;

	@BeforeEach
	void setUp() {
		this.orderRepository = mock(OrderRepository.class);
		this.productRepository = mock(ProductRepository.class);
		this.orderService = new OrderService(this.orderRepository, this.productRepository);
		given(this.orderRepository.save(org.mockito.ArgumentMatchers.any(Order.class)))
			.willAnswer((invocation) -> invocation.getArgument(0));
	}

	@Test
	void createsOrderFromServerSidePrices() {
		Product keyboard = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42, ProductStatus.ACTIVE);
		given(this.productRepository.findAllById(anyIterable())).willReturn(List.of(keyboard));

		Order order = this.orderService.create(request(item(1L, 2)));

		assertThat(order.getOrderNumber()).startsWith("ORD-");
		assertThat(order.getItems()).hasSize(1);
		OrderItem item = order.getItems().get(0);
		assertThat(item.getUnitPrice()).isEqualByComparingTo("129000.00");
		assertThat(item.getSubtotal()).isEqualByComparingTo("258000.00");
		verify(this.orderRepository).save(order);
	}

	@Test
	void sumsSubtotalsOfEveryItemIntoTotalAmount() {
		Product keyboard = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42, ProductStatus.ACTIVE);
		Product hub = product(3L, "USB-C 7-in-1 Hub", "59000.00", 78, ProductStatus.ACTIVE);
		given(this.productRepository.findAllById(anyIterable())).willReturn(List.of(keyboard, hub));

		Order order = this.orderService.create(request(item(1L, 2), item(3L, 1)));

		// 129000 * 2 + 59000 * 1
		assertThat(order.getTotalAmount()).isEqualByComparingTo("317000.00");
		assertThat(order.getItems()).extracting(OrderItem::getSubtotal)
			.usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
			.containsExactly(new BigDecimal("258000.00"), new BigDecimal("59000.00"));
	}

	@Test
	void storesProductNameAndPriceSnapshot() {
		Product keyboard = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42, ProductStatus.ACTIVE);
		given(this.productRepository.findAllById(anyIterable())).willReturn(List.of(keyboard));

		Order order = this.orderService.create(request(item(1L, 1)));

		OrderItem item = order.getItems().get(0);
		assertThat(item.getProductId()).isEqualTo(1L);
		assertThat(item.getProductName()).isEqualTo("Mechanical Keyboard 87-Key");
		assertThat(item.getUnitPrice()).isEqualByComparingTo("129000.00");
		// the item keeps no reference to the product entity itself
		assertThat(ReflectionTestUtils.getField(item, "productId")).isEqualTo(1L);
	}

	@Test
	void createsOrderInPendingPaymentStatus() {
		Product keyboard = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42, ProductStatus.ACTIVE);
		given(this.productRepository.findAllById(anyIterable())).willReturn(List.of(keyboard));

		Order order = this.orderService.create(request(item(1L, 1)));

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
	}

	@Test
	void rejectsDuplicateProductInSameRequest() {
		assertThatThrownBy(() -> this.orderService.create(request(item(1L, 1), item(1L, 2))))
			.isInstanceOf(DuplicateProductException.class)
			.extracting("productId")
			.isEqualTo(1L);

		verify(this.orderRepository, never()).save(org.mockito.ArgumentMatchers.any(Order.class));
	}

	@Test
	void rejectsUnknownProduct() {
		given(this.productRepository.findAllById(anyIterable())).willReturn(List.of());

		assertThatThrownBy(() -> this.orderService.create(request(item(404L, 1))))
			.isInstanceOf(ProductNotFoundException.class)
			.extracting("productId")
			.isEqualTo(404L);

		verify(this.orderRepository, never()).save(org.mockito.ArgumentMatchers.any(Order.class));
	}

	@Test
	void rejectsInactiveProduct() {
		Product discontinued = product(10L, "Wired Optical Mouse (Discontinued)", "9900.00", 0,
				ProductStatus.INACTIVE);
		given(this.productRepository.findAllById(anyIterable())).willReturn(List.of(discontinued));

		assertThatThrownBy(() -> this.orderService.create(request(item(10L, 1))))
			.isInstanceOf(ProductInactiveException.class)
			.extracting("productId")
			.isEqualTo(10L);

		verify(this.orderRepository, never()).save(org.mockito.ArgumentMatchers.any(Order.class));
	}

	@Test
	void doesNotChangeProductStockWhenCreatingOrder() {
		Product keyboard = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42, ProductStatus.ACTIVE);
		given(this.productRepository.findAllById(anyIterable())).willReturn(List.of(keyboard));

		this.orderService.create(request(item(1L, 40)));

		assertThat(keyboard.getStockQuantity()).isEqualTo(42);
		verify(this.productRepository, never()).save(org.mockito.ArgumentMatchers.any(Product.class));
	}

	@Test
	void allowsOrderingMoreThanAvailableStockForNow() {
		// stock is not reserved until payment, so ordering beyond stock still succeeds
		Product almostGone = product(4L, "27-inch QHD Monitor", "389000.00", 1, ProductStatus.ACTIVE);
		given(this.productRepository.findAllById(anyIterable())).willReturn(List.of(almostGone));

		Order order = this.orderService.create(request(item(4L, 5)));

		assertThat(order.getItems().get(0).getQuantity()).isEqualTo(5);
		assertThat(almostGone.getStockQuantity()).isEqualTo(1);
	}

	@Test
	void getByIdThrowsOrderNotFoundExceptionWhenMissing() {
		given(this.orderRepository.findByIdWithItems(999L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.orderService.getById(999L)).isInstanceOf(OrderNotFoundException.class)
			.extracting("orderId")
			.isEqualTo(999L);
	}

	private OrderCreateRequest request(OrderItemRequest... items) {
		return new OrderCreateRequest(List.of(items));
	}

	private OrderItemRequest item(Long productId, Integer quantity) {
		return new OrderItemRequest(productId, quantity);
	}

	private Product product(Long id, String name, String price, int stock, ProductStatus status) {
		Product product = new Product(name, "description", new BigDecimal(price), stock, status);
		ReflectionTestUtils.setField(product, "id", id);
		return product;
	}

}

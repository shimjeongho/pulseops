package com.pulseops.demoshop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pulseops.demoshop.order.Order;
import com.pulseops.demoshop.order.OrderAlreadyPaidException;
import com.pulseops.demoshop.order.OrderItem;
import com.pulseops.demoshop.order.OrderNotFoundException;
import com.pulseops.demoshop.order.OrderRepository;
import com.pulseops.demoshop.order.OrderStatus;
import com.pulseops.demoshop.payment.dto.PaymentCreateRequest;
import com.pulseops.demoshop.product.InsufficientStockException;
import com.pulseops.demoshop.product.Product;
import com.pulseops.demoshop.product.ProductNotFoundException;
import com.pulseops.demoshop.product.ProductRepository;
import com.pulseops.demoshop.product.ProductStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Repository를 Stub으로 대체하고 결제 규칙 자체만 검증한다. 이 규칙이 기대는 데이터베이스 수준
 * 보장인 Transaction과 행 잠금은 {@code PaymentApiIntegrationTest}와
 * {@code PaymentConcurrencyIntegrationTest}에서 검증한다.
 */
class PaymentServiceTest {

	private PaymentRepository paymentRepository;

	private OrderRepository orderRepository;

	private ProductRepository productRepository;

	private PaymentService paymentService;

	@BeforeEach
	void setUp() {
		this.paymentRepository = mock(PaymentRepository.class);
		this.orderRepository = mock(OrderRepository.class);
		this.productRepository = mock(ProductRepository.class);
		this.paymentService = new PaymentService(this.paymentRepository, this.orderRepository,
				this.productRepository);
		given(this.paymentRepository.save(any(Payment.class)))
			.willAnswer((invocation) -> invocation.getArgument(0));
	}

	// --- 결제 성공 ----------------------------------------------------------

	@Test
	void paysForAPendingOrder() {
		Product keyboard = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42);
		Order order = pendingOrder(100L, item(keyboard, 2));
		stub(order, keyboard);

		Payment payment = this.paymentService.pay(new PaymentCreateRequest(100L));

		assertThat(payment.getPaymentNumber()).startsWith("PAY-");
		assertThat(payment.getOrder()).isSameAs(order);
		verify(this.paymentRepository).save(payment);
	}

	@Test
	void chargesTheOrderTotalRatherThanAnAmountFromTheCaller() {
		Product keyboard = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42);
		Product hub = product(3L, "USB-C 7-in-1 Hub", "59000.00", 78);
		Order order = pendingOrder(100L, item(keyboard, 2), item(hub, 1));
		stub(order, keyboard, hub);

		Payment payment = this.paymentService.pay(new PaymentCreateRequest(100L));

		// 129000 * 2 + 59000
		assertThat(order.getTotalAmount()).isEqualByComparingTo("317000.00");
		assertThat(payment.getAmount()).isEqualByComparingTo(order.getTotalAmount());
	}

	@Test
	void recordsThePaymentAsSuccessful() {
		Product keyboard = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42);
		Order order = pendingOrder(100L, item(keyboard, 1));
		stub(order, keyboard);

		Payment payment = this.paymentService.pay(new PaymentCreateRequest(100L));

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
	}

	@Test
	void leavesNoFailureDetailsOnASuccessfulPayment() {
		Product keyboard = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42);
		Order order = pendingOrder(100L, item(keyboard, 1));
		stub(order, keyboard);

		Payment payment = this.paymentService.pay(new PaymentCreateRequest(100L));

		assertThat(payment.getFailureCode()).isNull();
		assertThat(payment.getFailureMessage()).isNull();
	}

	@Test
	void takesTheOrderedQuantityOffEveryProduct() {
		Product keyboard = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42);
		Product hub = product(3L, "USB-C 7-in-1 Hub", "59000.00", 78);
		Order order = pendingOrder(100L, item(keyboard, 2), item(hub, 5));
		stub(order, keyboard, hub);

		this.paymentService.pay(new PaymentCreateRequest(100L));

		assertThat(keyboard.getStockQuantity()).isEqualTo(40);
		assertThat(hub.getStockQuantity()).isEqualTo(73);
	}

	@Test
	void marksTheOrderPaid() {
		Product keyboard = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42);
		Order order = pendingOrder(100L, item(keyboard, 1));
		stub(order, keyboard);

		this.paymentService.pay(new PaymentCreateRequest(100L));

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
	}

	@Test
	void takesExactlyTheAvailableStockWhenTheOrderClearsTheShelf() {
		Product lastOne = product(4L, "27-inch QHD Monitor", "389000.00", 1);
		Order order = pendingOrder(100L, item(lastOne, 1));
		stub(order, lastOne);

		Payment payment = this.paymentService.pay(new PaymentCreateRequest(100L));

		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
		assertThat(lastOne.getStockQuantity()).isZero();
	}

	@Test
	void readsStockOnlyThroughTheLockingFinder() {
		Product keyboard = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42);
		Order order = pendingOrder(100L, item(keyboard, 1));
		stub(order, keyboard);

		this.paymentService.pay(new PaymentCreateRequest(100L));

		verify(this.productRepository).findAllByIdInOrderByIdAsc(anyCollection());
		verify(this.productRepository, never()).findAllById(anyIterable());
		verify(this.productRepository, never()).findById(anyLong());
	}

	// --- 주문 이후 가격 변경 ------------------------------------------------

	@Test
	void chargesTheOrderTotalEvenAfterTheProductPriceHasChanged() {
		Product keyboard = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42);
		Order order = pendingOrder(100L, item(keyboard, 2));
		BigDecimal totalWhenOrdered = order.getTotalAmount();
		// 주문이 생성되고 한참 뒤에 상품 가격이 변경된 상황을 가정한다
		ReflectionTestUtils.setField(keyboard, "price", new BigDecimal("999000.00"));
		stub(order, keyboard);

		Payment payment = this.paymentService.pay(new PaymentCreateRequest(100L));

		assertThat(payment.getAmount()).isEqualByComparingTo(totalWhenOrdered);
		assertThat(payment.getAmount()).isEqualByComparingTo("258000.00");
	}

	// --- 결제 거부 ----------------------------------------------------------

	@Test
	void rejectsPaymentForAnUnknownOrder() {
		given(this.orderRepository.findByIdWithItems(999L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.paymentService.pay(new PaymentCreateRequest(999L)))
			.isInstanceOf(OrderNotFoundException.class)
			.extracting("orderId")
			.isEqualTo(999L);

		verify(this.paymentRepository, never()).save(any(Payment.class));
	}

	@Test
	void rejectsASecondPaymentForAnOrderThatIsAlreadyPaid() {
		Product keyboard = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42);
		Order order = pendingOrder(100L, item(keyboard, 2));
		order.markPaid();
		stub(order, keyboard);

		assertThatThrownBy(() -> this.paymentService.pay(new PaymentCreateRequest(100L)))
			.isInstanceOf(OrderAlreadyPaidException.class)
			.extracting("orderId")
			.isEqualTo(100L);

		assertThat(keyboard.getStockQuantity()).isEqualTo(42);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		verify(this.paymentRepository, never()).save(any(Payment.class));
	}

	@Test
	void rejectsPaymentWhenAProductHasSinceDisappeared() {
		Product keyboard = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42);
		Order order = pendingOrder(100L, item(keyboard, 1));
		given(this.orderRepository.findByIdWithItems(100L)).willReturn(Optional.of(order));
		given(this.productRepository.findAllByIdInOrderByIdAsc(anyCollection())).willReturn(List.of());

		assertThatThrownBy(() -> this.paymentService.pay(new PaymentCreateRequest(100L)))
			.isInstanceOf(ProductNotFoundException.class)
			.extracting("productId")
			.isEqualTo(1L);

		verify(this.paymentRepository, never()).save(any(Payment.class));
	}

	@Test
	void rejectsPaymentWhenStockIsShort() {
		Product almostGone = product(4L, "27-inch QHD Monitor", "389000.00", 1);
		Order order = pendingOrder(100L, item(almostGone, 2));
		stub(order, almostGone);

		assertThatThrownBy(() -> this.paymentService.pay(new PaymentCreateRequest(100L)))
			.isInstanceOf(InsufficientStockException.class)
			.extracting("productId")
			.isEqualTo(4L);
	}

	@Test
	void leavesStockOrderAndPaymentUntouchedWhenStockIsShort() {
		Product almostGone = product(4L, "27-inch QHD Monitor", "389000.00", 1);
		Order order = pendingOrder(100L, item(almostGone, 2));
		stub(order, almostGone);

		assertThatThrownBy(() -> this.paymentService.pay(new PaymentCreateRequest(100L)))
			.isInstanceOf(InsufficientStockException.class);

		assertThat(almostGone.getStockQuantity()).isEqualTo(1);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
		verify(this.paymentRepository, never()).save(any(Payment.class));
	}

	/**
	 * 재고 부족이 두 번째 항목에서 발생하므로, 항목 순서대로 차감하는 구현이라면 부족을 알아챈
	 * 시점에 이미 첫 번째 상품의 재고를 차감한 뒤일 것이다.
	 */
	@Test
	void takesNoStockAtAllWhenALaterLineIsShort() {
		Product plentiful = product(1L, "Mechanical Keyboard 87-Key", "129000.00", 42);
		Product almostGone = product(4L, "27-inch QHD Monitor", "389000.00", 1);
		Order order = pendingOrder(100L, item(plentiful, 2), item(almostGone, 5));
		stub(order, plentiful, almostGone);

		assertThatThrownBy(() -> this.paymentService.pay(new PaymentCreateRequest(100L)))
			.isInstanceOf(InsufficientStockException.class)
			.extracting("productId")
			.isEqualTo(4L);

		assertThat(plentiful.getStockQuantity()).isEqualTo(42);
		assertThat(almostGone.getStockQuantity()).isEqualTo(1);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
		verify(this.paymentRepository, never()).save(any(Payment.class));
	}

	// --- 헬퍼 ---------------------------------------------------------------

	private void stub(Order order, Product... products) {
		given(this.orderRepository.findByIdWithItems(order.getId())).willReturn(Optional.of(order));
		given(this.productRepository.findAllByIdInOrderByIdAsc(anyCollection())).willReturn(List.of(products));
	}

	private Order pendingOrder(Long id, OrderItem... items) {
		Order order = new Order("ORD-test-" + id, List.of(items));
		ReflectionTestUtils.setField(order, "id", id);
		return order;
	}

	private OrderItem item(Product product, int quantity) {
		return new OrderItem(product.getId(), product.getName(), product.getPrice(), quantity);
	}

	private Product product(Long id, String name, String price, int stock) {
		Product product = new Product(name, "description", new BigDecimal(price), stock, ProductStatus.ACTIVE);
		ReflectionTestUtils.setField(product, "id", id);
		return product;
	}

}

package com.pulseops.demoshop.payment;

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
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

	private static final String PAYMENT_NUMBER_PREFIX = "PAY-";

	private final PaymentRepository paymentRepository;

	private final OrderRepository orderRepository;

	private final ProductRepository productRepository;

	public PaymentService(PaymentRepository paymentRepository, OrderRepository orderRepository,
			ProductRepository productRepository) {
		this.paymentRepository = paymentRepository;
		this.orderRepository = orderRepository;
		this.productRepository = productRepository;
	}

	/**
	 * 주문을 결제한다. 재고 차감, 주문 상태 변경, 결제 기록을 하나의 Transaction에서 처리한다.
	 * 셋 모두 반영되거나 모두 반영되지 않으므로, 결제 기록이나 재고 차감 없이 결제 완료된 주문이
	 * 남는 일은 없다.
	 * <p>
	 * 검증에 앞서 상품 행에 쓰기 잠금을 걸어, 마지막 재고를 두고 경합하는 두 결제가 동시에
	 * 성공하지 않고 직렬화되도록 한다. 또한 어떤 재고도 차감하기 전에 모든 상품을 먼저 검증하므로
	 * 마지막 항목에서 재고가 부족해도 앞선 항목의 재고가 이미 차감되어 있지 않다.
	 */
	@Transactional
	public Payment pay(PaymentCreateRequest request) {
		Order order = this.orderRepository.findByIdWithItems(request.orderId())
			.orElseThrow(() -> new OrderNotFoundException(request.orderId()));
		if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
			throw new OrderAlreadyPaidException(order.getId());
		}

		SortedMap<Long, Integer> quantitiesByProductId = quantitiesByProductId(order);
		Map<Long, Product> lockedProducts = lockProducts(quantitiesByProductId);

		quantitiesByProductId.forEach((productId, quantity) -> {
			Product product = lockedProducts.get(productId);
			if (product == null) {
				throw new ProductNotFoundException(productId);
			}
			if (!product.hasStock(quantity)) {
				throw new InsufficientStockException(product.getId(), product.getName(), quantity,
						product.getStockQuantity());
			}
		});

		// 이 시점에는 모든 항목의 재고가 충분함이 확인되었으므로 차감 도중 실패할 수 없다
		quantitiesByProductId.forEach((productId, quantity) -> lockedProducts.get(productId).decreaseStock(quantity));

		order.markPaid();
		return this.paymentRepository.save(Payment.success(generatePaymentNumber(), order));
	}

	/**
	 * 상품 ID 오름차순으로 정렬해 반환한다. 주문 항목이 어떤 순서로 들어오든 항상 같은 순서로
	 * 잠금을 획득하게 하기 위한 것이다.
	 */
	private SortedMap<Long, Integer> quantitiesByProductId(Order order) {
		SortedMap<Long, Integer> quantities = new TreeMap<>();
		for (OrderItem item : order.getItems()) {
			quantities.merge(item.getProductId(), item.getQuantity(), Integer::sum);
		}
		return quantities;
	}

	private Map<Long, Product> lockProducts(SortedMap<Long, Integer> quantitiesByProductId) {
		return this.productRepository.findAllByIdInOrderByIdAsc(quantitiesByProductId.keySet())
			.stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));
	}

	private String generatePaymentNumber() {
		return PAYMENT_NUMBER_PREFIX + UUID.randomUUID();
	}

}

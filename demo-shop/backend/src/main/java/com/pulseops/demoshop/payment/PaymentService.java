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
	 * Pays for an order: the stock is taken, the order is marked paid and the payment is
	 * recorded, all inside one transaction. Either all three happen or none does, so a
	 * paid order can never exist without its payment or without its stock deducted.
	 * <p>
	 * The products are locked for update before anything is checked, so two payments
	 * racing for the last item of stock are serialised rather than both succeeding. Every
	 * product is verified before any of them is decreased, so a shortage on the last line
	 * does not leave the earlier lines already taken.
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

		// every line is known to be coverable by this point, so nothing can fail half-way
		quantitiesByProductId.forEach((productId, quantity) -> lockedProducts.get(productId).decreaseStock(quantity));

		order.markPaid();
		return this.paymentRepository.save(Payment.success(generatePaymentNumber(), order));
	}

	/**
	 * Keyed by product id in ascending order, so the products are always locked in the
	 * same sequence no matter how the order's lines happen to be arranged.
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

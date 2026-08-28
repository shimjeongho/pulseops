package com.pulseops.demoshop.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseops.demoshop.TestcontainersConfiguration;
import com.pulseops.demoshop.order.OrderService;
import com.pulseops.demoshop.order.dto.OrderCreateRequest;
import com.pulseops.demoshop.order.dto.OrderItemRequest;
import com.pulseops.demoshop.payment.dto.PaymentCreateRequest;
import com.pulseops.demoshop.product.InsufficientStockException;
import com.pulseops.demoshop.product.Product;
import com.pulseops.demoshop.product.ProductRepository;
import com.pulseops.demoshop.product.ProductStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Two payments racing for the last item in stock, run as genuinely parallel
 * transactions against a real MySQL 8.4 container. A barrier holds both threads until
 * they are ready, so the two payments really do overlap instead of quietly running one
 * after the other and proving nothing.
 * Requires Docker; skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentConcurrencyIntegrationTest {

	private static final String TEST_PRODUCT_PREFIX = "Concurrency Test Product ";

	private static final int BARRIER_TIMEOUT_SECONDS = 30;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private OrderService orderService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * The product created here is removed again so that tests elsewhere, which count the
	 * seeded catalogue, still see exactly what the seed migration inserted.
	 */
	@AfterEach
	void removeProductsCreatedByThisTest() {
		this.jdbcTemplate.update("DELETE FROM product WHERE name LIKE ?", TEST_PRODUCT_PREFIX + "%");
	}

	@Test
	void onlyOneOfTwoPaymentsForTheLastItemInStockSucceeds() throws Exception {
		Product lastOne = this.productRepository.save(new Product(TEST_PRODUCT_PREFIX + UUID.randomUUID(),
				"created by PaymentConcurrencyIntegrationTest", new BigDecimal("2000.00"), 1, ProductStatus.ACTIVE));
		Long orderA = newOrderFor(lastOne);
		Long orderB = newOrderFor(lastOne);

		List<Optional<Throwable>> outcomes = payInParallel(orderA, orderB);

		List<Optional<Throwable>> successes = outcomes.stream().filter(Optional::isEmpty).toList();
		List<Throwable> failures = outcomes.stream().flatMap(Optional::stream).toList();

		assertThat(successes).as("exactly one payment may succeed").hasSize(1);
		assertThat(failures).as("the loser is turned away for want of stock, not by some other error")
			.singleElement()
			.isInstanceOf(InsufficientStockException.class);

		assertThat(stockOf(lastOne)).as("the shelf is emptied exactly once and never oversold").isZero();
		assertThat(successfulPaymentsFor(orderA, orderB)).isEqualTo(1);
		assertThat(List.of(statusOfOrder(orderA), statusOfOrder(orderB)))
			.containsExactlyInAnyOrder("PAID", "PENDING_PAYMENT");
	}

	private List<Optional<Throwable>> payInParallel(Long firstOrderId, Long secondOrderId) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CyclicBarrier barrier = new CyclicBarrier(2);
		try {
			List<Future<Optional<Throwable>>> futures = executor
				.invokeAll(List.of(attempt(barrier, firstOrderId), attempt(barrier, secondOrderId)));
			List<Optional<Throwable>> outcomes = new ArrayList<>();
			for (Future<Optional<Throwable>> future : futures) {
				outcomes.add(future.get(BARRIER_TIMEOUT_SECONDS * 2L, TimeUnit.SECONDS));
			}
			return outcomes;
		}
		finally {
			executor.shutdownNow();
		}
	}

	private Callable<Optional<Throwable>> attempt(CyclicBarrier barrier, Long orderId) {
		return () -> {
			barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			try {
				this.paymentService.pay(new PaymentCreateRequest(orderId));
				return Optional.empty();
			}
			catch (Throwable failure) {
				return Optional.of(failure);
			}
		};
	}

	private Long newOrderFor(Product product) {
		return this.orderService
			.create(new OrderCreateRequest(List.of(new OrderItemRequest(product.getId(), 1))))
			.getId();
	}

	private int stockOf(Product product) {
		return this.jdbcTemplate.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Integer.class,
				product.getId());
	}

	private String statusOfOrder(Long orderId) {
		return this.jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
	}

	private int successfulPaymentsFor(Long firstOrderId, Long secondOrderId) {
		return this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM payment WHERE status = 'SUCCESS' AND order_id IN (?, ?)", Integer.class,
				firstOrderId, secondOrderId);
	}

}

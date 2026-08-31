package com.pulseops.demoshop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pulseops.demoshop.TestcontainersConfiguration;
import com.pulseops.demoshop.order.Order;
import com.pulseops.demoshop.order.OrderService;
import com.pulseops.demoshop.order.dto.OrderCreateRequest;
import com.pulseops.demoshop.order.dto.OrderItemRequest;
import com.pulseops.demoshop.product.Product;
import com.pulseops.demoshop.product.ProductRepository;
import com.pulseops.demoshop.product.ProductStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 MySQL 8.4 Container를 대상으로 Payment API를 검증한다. V4 Migration, 결제 Transaction,
 * 오류 응답 규약을 실제 실행 환경과 동일하게 확인한다. 각 테스트는 자신이 쓸 상품을 직접
 * 생성하므로 한 테스트가 다른 테스트가 의존하는 재고를 소진시키지 않는다.
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
class PaymentApiIntegrationTest {

	private static final String TEST_PRODUCT_PREFIX = "Payment Test Product ";

	private static final long UNKNOWN_ORDER_ID = 999999L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private OrderService orderService;

	/**
	 * 여기서 생성한 상품은 다시 제거한다. 초기 데이터 개수를 확인하는 다른 테스트가 Seed
	 * Migration이 삽입한 데이터만 보도록 하기 위한 것이다.
	 */
	@AfterEach
	void removeProductsCreatedByThisTest() {
		this.jdbcTemplate.update("DELETE FROM product WHERE name LIKE ?", TEST_PRODUCT_PREFIX + "%");
	}

	// --- Migration ----------------------------------------------------------

	@Test
	void flywayAppliesAllFourMigrations() {
		assertThat(this.jdbcTemplate.queryForList(
				"SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank", String.class))
			.containsExactly("1", "2", "3", "4");
	}

	@Test
	void migrationCreatesThePaymentTable() {
		assertThat(this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables "
						+ "WHERE table_schema = DATABASE() AND table_name = 'payment'",
				Integer.class)).isEqualTo(1);
	}

	@Test
	void paymentTableCarriesTheExpectedConstraints() {
		List<String> constraints = this.jdbcTemplate.queryForList(
				"SELECT constraint_name FROM information_schema.table_constraints "
						+ "WHERE table_schema = DATABASE() AND table_name = 'payment'",
				String.class);

		assertThat(constraints).contains("fk_payment_order", "uk_payment_payment_number", "ck_payment_amount");
	}

	/**
	 * 하나의 주문에 여러 번의 결제 시도가 있을 수 있으므로, 외래 키가 주문당 결제 행을 하나로
	 * 제한하는 Unique Index로 뒷받침되어서는 안 된다.
	 */
	@Test
	void paymentOrderIdIsNotUnique() {
		assertThat(this.jdbcTemplate.queryForList(
				"SELECT index_name FROM information_schema.statistics "
						+ "WHERE table_schema = DATABASE() AND table_name = 'payment' "
						+ "AND column_name = 'order_id' AND non_unique = 0",
				String.class)).isEmpty();
	}

	@Test
	void paymentAmountCheckConstraintRejectsNegativeAmounts() {
		Product product = newProduct("2000.00", 5);
		Long orderId = newOrder(product, 1);

		assertThatThrownBy(() -> this.jdbcTemplate.update(
				"INSERT INTO payment (payment_number, order_id, amount, status, created_at) "
						+ "VALUES (?, ?, ?, ?, NOW(6))",
				"PAY-" + UUID.randomUUID(), orderId, new BigDecimal("-1.00"), "FAILED"))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_payment_amount");

		assertThat(paymentCountFor(orderId)).isZero();
	}

	// --- 결제 성공 ----------------------------------------------------------

	@Test
	void paysForAPendingOrder() throws Exception {
		Product product = newProduct("2000.00", 5);
		Long orderId = newOrder(product, 2);

		this.mockMvc.perform(paymentRequest("{\"orderId\":%d}".formatted(orderId)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isNumber())
			.andExpect(jsonPath("$.paymentNumber", startsWith("PAY-")))
			.andExpect(jsonPath("$.orderId").value(orderId.intValue()))
			.andExpect(jsonPath("$.status").value("SUCCESS"))
			.andExpect(jsonPath("$.amount").value(4000.00))
			.andExpect(jsonPath("$.createdAt").exists());
	}

	@Test
	void chargesTheOrderTotal() throws Exception {
		Product first = newProduct("2000.00", 5);
		Product second = newProduct("350.00", 5);
		Long orderId = newOrder(first, 2, second, 3);
		BigDecimal orderTotal = totalAmountOf(orderId);

		// 2000 * 2 + 350 * 3
		assertThat(orderTotal).isEqualByComparingTo("5050.00");
		pay(orderId);

		assertThat(this.jdbcTemplate.queryForObject("SELECT amount FROM payment WHERE order_id = ?", BigDecimal.class,
				orderId)).isEqualByComparingTo(orderTotal);
	}

	@Test
	void storesTheSuccessfulPaymentWithoutFailureDetails() throws Exception {
		Product product = newProduct("2000.00", 5);
		Long orderId = newOrder(product, 1);

		pay(orderId);

		Map<String, Object> row = this.jdbcTemplate
			.queryForMap("SELECT status, failure_code, failure_message FROM payment WHERE order_id = ?", orderId);
		assertThat(row.get("status")).isEqualTo("SUCCESS");
		assertThat(row.get("failure_code")).isNull();
		assertThat(row.get("failure_message")).isNull();
	}

	@Test
	void marksTheOrderPaid() throws Exception {
		Product product = newProduct("2000.00", 5);
		Long orderId = newOrder(product, 1);

		pay(orderId);

		assertThat(statusOfOrder(orderId)).isEqualTo("PAID");
	}

	/**
	 * 주문 생성은 재고를 건드리지 않고 결제만 재고를 차감한다.
	 * Phase 1-3 규칙과 Phase 1-4 규칙을 한 번의 흐름에서 함께 확인한다.
	 */
	@Test
	void takesStockAtPaymentAndNotAtOrderCreation() throws Exception {
		Product product = newProduct("2000.00", 5);
		Long orderId = newOrder(product, 2);

		assertThat(stockOf(product)).isEqualTo(5);

		pay(orderId);

		assertThat(stockOf(product)).isEqualTo(3);
	}

	@Test
	void chargesTheOrderTotalEvenAfterTheProductPriceHasChanged() throws Exception {
		Product product = newProduct("2000.00", 5);
		Long orderId = newOrder(product, 2);
		BigDecimal orderTotal = totalAmountOf(orderId);

		this.jdbcTemplate.update("UPDATE product SET price = ? WHERE id = ?", new BigDecimal("9999.00"),
				product.getId());

		String body = pay(orderId);

		assertThat(new BigDecimal(JsonPath.read(body, "$.amount").toString())).isEqualByComparingTo(orderTotal);
		assertThat(new BigDecimal(JsonPath.read(body, "$.amount").toString())).isEqualByComparingTo("4000.00");
	}

	@Test
	void responseExposesOnlyThePaymentSummary() throws Exception {
		Product product = newProduct("2000.00", 5);
		Long orderId = newOrder(product, 1);

		Map<String, Object> body = JsonPath.read(pay(orderId), "$");

		assertThat(body.keySet()).containsExactlyInAnyOrder("id", "paymentNumber", "orderId", "amount", "status",
				"createdAt");
	}

	@Test
	void paymentsGetDistinctPaymentNumbers() throws Exception {
		Product product = newProduct("2000.00", 5);
		String first = JsonPath.read(pay(newOrder(product, 1)), "$.paymentNumber");
		String second = JsonPath.read(pay(newOrder(product, 1)), "$.paymentNumber");

		assertThat(first).isNotEqualTo(second);
	}

	// --- 주문 상태 오류 -----------------------------------------------------

	@Test
	void rejectsPaymentForAnUnknownOrderWithNotFound() throws Exception {
		expectRejected("{\"orderId\":%d}".formatted(UNKNOWN_ORDER_ID), 404, "ORDER_NOT_FOUND");
	}

	@Test
	void rejectsASecondPaymentForAnOrderThatIsAlreadyPaidWithConflict() throws Exception {
		Product product = newProduct("2000.00", 5);
		Long orderId = newOrder(product, 2);
		pay(orderId);

		expectRejected("{\"orderId\":%d}".formatted(orderId), 409, "ORDER_ALREADY_PAID");
	}

	@Test
	void aRejectedSecondPaymentChangesNothing() throws Exception {
		Product product = newProduct("2000.00", 5);
		Long orderId = newOrder(product, 2);
		pay(orderId);

		expectRejected("{\"orderId\":%d}".formatted(orderId), 409, "ORDER_ALREADY_PAID");

		assertThat(paymentCountFor(orderId)).isEqualTo(1);
		assertThat(stockOf(product)).isEqualTo(3);
		assertThat(statusOfOrder(orderId)).isEqualTo("PAID");
	}

	// --- 재고 부족 ----------------------------------------------------------

	@Test
	void rejectsPaymentWhenStockIsShortWithConflict() throws Exception {
		Product product = newProduct("2000.00", 1);
		Long orderId = newOrder(product, 2);

		expectRejected("{\"orderId\":%d}".formatted(orderId), 409, "INSUFFICIENT_STOCK");
	}

	@Test
	void aShortStockPaymentLeavesStockOrderAndPaymentsUntouched() throws Exception {
		Product product = newProduct("2000.00", 1);
		Long orderId = newOrder(product, 2);

		expectRejected("{\"orderId\":%d}".formatted(orderId), 409, "INSUFFICIENT_STOCK");

		assertThat(stockOf(product)).isEqualTo(1);
		assertThat(statusOfOrder(orderId)).isEqualTo("PENDING_PAYMENT");
		assertThat(paymentCountFor(orderId)).isZero();
	}

	/**
	 * 재고 부족이 주문의 두 번째 항목에서 발생한다. Transaction은 첫 번째 상품을 차감했다가
	 * 일부만 되돌리는 것이 아니라 아예 건드리지 않은 상태로 남겨야 한다.
	 */
	@Test
	void takesNoStockAtAllWhenOneLineOfAMultiProductOrderIsShort() throws Exception {
		Product plentiful = newProduct("2000.00", 10);
		Product almostGone = newProduct("350.00", 1);
		Long orderId = newOrder(plentiful, 2, almostGone, 5);

		expectRejected("{\"orderId\":%d}".formatted(orderId), 409, "INSUFFICIENT_STOCK");

		assertThat(stockOf(plentiful)).isEqualTo(10);
		assertThat(stockOf(almostGone)).isEqualTo(1);
		assertThat(statusOfOrder(orderId)).isEqualTo("PENDING_PAYMENT");
		assertThat(paymentCountFor(orderId)).isZero();
	}

	@Test
	void errorMessageNamesTheProductThatIsShortWithoutLeakingInternals() throws Exception {
		Product almostGone = newProduct("350.00", 1);
		Long orderId = newOrder(almostGone, 5);

		String body = this.mockMvc.perform(paymentRequest("{\"orderId\":%d}".formatted(orderId)))
			.andExpect(status().isConflict())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(JsonPath.read(body, "$.message").toString()).contains(almostGone.getName());
		assertThat(body).doesNotContain("Exception").doesNotContain("com.pulseops");
	}

	// --- Validation 실패 ----------------------------------------------------

	@Test
	void rejectsMissingOrderId() throws Exception {
		expectRejected("{\"orderId\":null}", 400, "VALIDATION_ERROR");
	}

	@Test
	void rejectsEmptyBody() throws Exception {
		expectRejected("{}", 400, "VALIDATION_ERROR");
	}

	@Test
	void validationFailureNamesTheOffendingField() throws Exception {
		String body = this.mockMvc.perform(paymentRequest("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors", hasSize(greaterThanOrEqualTo(1))))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).contains("orderId");
	}

	// --- 노출 범위 ----------------------------------------------------------

	/**
	 * 결제 조회, 변경, 환불, 취소는 현재 Phase의 범위가 아니므로 관련 Endpoint가 아직 존재해서는
	 * 안 된다.
	 */
	@Test
	void exposesNoEndpointBesidesCreatingAPayment() throws Exception {
		this.mockMvc.perform(get("/api/payments")).andExpect(status().isMethodNotAllowed());
		this.mockMvc.perform(put("/api/payments")).andExpect(status().isMethodNotAllowed());
		this.mockMvc.perform(patch("/api/payments")).andExpect(status().isMethodNotAllowed());
		this.mockMvc.perform(delete("/api/payments")).andExpect(status().isMethodNotAllowed());
		this.mockMvc.perform(get("/api/payments/1")).andExpect(status().isNotFound());
		this.mockMvc.perform(delete("/api/payments/1")).andExpect(status().isNotFound());
		this.mockMvc.perform(post("/api/payments/1/refund")).andExpect(status().isNotFound());
		this.mockMvc.perform(post("/api/payments/1/cancel")).andExpect(status().isNotFound());
		this.mockMvc.perform(post("/api/payments/1/retry")).andExpect(status().isNotFound());
	}

	// --- 회귀 ---------------------------------------------------------------

	@Test
	void productApiStillWorks() throws Exception {
		Product product = newProduct("2000.00", 5);

		this.mockMvc.perform(get("/api/products"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(10))))
			.andExpect(jsonPath("$[0].name").isString());
		this.mockMvc.perform(get("/api/products/{productId}", product.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value(product.getName()))
			.andExpect(jsonPath("$.stockQuantity").value(5));
	}

	@Test
	void orderApiStillWorks() throws Exception {
		Product product = newProduct("2000.00", 5);

		String body = this.mockMvc
			.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
				.content("{\"items\":[{\"productId\":%d,\"quantity\":2}]}".formatted(product.getId())))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
			.andExpect(jsonPath("$.totalAmount").value(4000.00))
			.andReturn()
			.getResponse()
			.getContentAsString();

		Integer orderId = JsonPath.read(body, "$.id");
		this.mockMvc.perform(get("/api/orders/{orderId}", orderId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)));
		// 주문 생성은 여전히 재고를 차감하지 않는다
		assertThat(stockOf(product)).isEqualTo(5);
	}

	// --- 헬퍼 ---------------------------------------------------------------

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder paymentRequest(String body) {
		return post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private String pay(Long orderId) throws Exception {
		return this.mockMvc.perform(paymentRequest("{\"orderId\":%d}".formatted(orderId)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private void expectRejected(String requestBody, int expectedStatus, String expectedCode) throws Exception {
		this.mockMvc.perform(paymentRequest(requestBody))
			.andExpect(status().is(expectedStatus))
			.andExpect(jsonPath("$.status").value(expectedStatus))
			.andExpect(jsonPath("$.code").value(expectedCode))
			.andExpect(jsonPath("$.path").value("/api/payments"))
			.andExpect(jsonPath("$.message").isString())
			.andExpect(jsonPath("$.timestamp").isString())
			.andExpect(jsonPath("$.fieldErrors").isArray())
			.andExpect(jsonPath("$.trace").doesNotExist())
			.andExpect(jsonPath("$.stackTrace").doesNotExist());
	}

	private Product newProduct(String price, int stock) {
		return this.productRepository.save(new Product(TEST_PRODUCT_PREFIX + UUID.randomUUID(),
				"created by PaymentApiIntegrationTest", new BigDecimal(price), stock, ProductStatus.ACTIVE));
	}

	private Long newOrder(Product product, int quantity) {
		return createOrder(List.of(new OrderItemRequest(product.getId(), quantity)));
	}

	private Long newOrder(Product first, int firstQuantity, Product second, int secondQuantity) {
		return createOrder(List.of(new OrderItemRequest(first.getId(), firstQuantity),
				new OrderItemRequest(second.getId(), secondQuantity)));
	}

	private Long createOrder(List<OrderItemRequest> items) {
		Order order = this.orderService.create(new OrderCreateRequest(items));
		return order.getId();
	}

	private int stockOf(Product product) {
		return this.jdbcTemplate.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Integer.class,
				product.getId());
	}

	private String statusOfOrder(Long orderId) {
		return this.jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
	}

	private BigDecimal totalAmountOf(Long orderId) {
		return this.jdbcTemplate.queryForObject("SELECT total_amount FROM orders WHERE id = ?", BigDecimal.class,
				orderId);
	}

	private int paymentCountFor(Long orderId) {
		return this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class,
				orderId);
	}

}

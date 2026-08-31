package com.pulseops.demoshop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pulseops.demoshop.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 MySQL 8.4 Container를 대상으로 Order API를 검증한다. Flyway Migration,
 * Order/OrderItem Mapping, 오류 응답 규약을 실제 실행 환경과 동일하게 확인한다.
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "pulseops.telemetry.enabled=false")
class OrderApiIntegrationTest {

	private static final long ACTIVE_KEYBOARD_ID = 1L;

	private static final long ACTIVE_HUB_ID = 3L;

	private static final long INACTIVE_PRODUCT_ID = 10L;

	private static final long UNKNOWN_PRODUCT_ID = 999999L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// --- Migration ----------------------------------------------------------

	@Test
	void flywayAppliesTheOrderMigration() {
		assertThat(this.jdbcTemplate.queryForList(
				"SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank", String.class))
			.startsWith("1", "2", "3");
	}

	@Test
	void migrationCreatesOrderTables() {
		assertThat(tableExists("orders")).isEqualTo(1);
		assertThat(tableExists("order_item")).isEqualTo(1);
	}

	@Test
	void orderTablesCarryTheExpectedConstraints() {
		assertThat(constraintsOf("orders")).contains("uk_orders_order_number", "ck_orders_total_amount");
		assertThat(constraintsOf("order_item")).contains("fk_order_item_order", "ck_order_item_unit_price",
				"ck_order_item_quantity", "ck_order_item_subtotal");
	}

	// --- 생성 ---------------------------------------------------------------

	@Test
	void createsOrderPricedFromServerProductData() throws Exception {
		BigDecimal keyboardPrice = priceOf(ACTIVE_KEYBOARD_ID);
		BigDecimal hubPrice = priceOf(ACTIVE_HUB_ID);
		BigDecimal expectedTotal = keyboardPrice.multiply(BigDecimal.valueOf(2)).add(hubPrice);

		this.mockMvc
			.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
				.content(orderJson(ACTIVE_KEYBOARD_ID, 2, ACTIVE_HUB_ID, 1)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isNumber())
			.andExpect(jsonPath("$.orderNumber", startsWith("ORD-")))
			.andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
			.andExpect(jsonPath("$.totalAmount").value(expectedTotal.doubleValue()))
			.andExpect(jsonPath("$.createdAt").exists())
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[0].productId").value((int) ACTIVE_KEYBOARD_ID))
			.andExpect(jsonPath("$.items[0].productName").value(nameOf(ACTIVE_KEYBOARD_ID)))
			.andExpect(jsonPath("$.items[0].unitPrice").value(keyboardPrice.doubleValue()))
			.andExpect(jsonPath("$.items[0].quantity").value(2))
			.andExpect(jsonPath("$.items[0].subtotal")
				.value(keyboardPrice.multiply(BigDecimal.valueOf(2)).doubleValue()))
			.andExpect(jsonPath("$.items[1].productId").value((int) ACTIVE_HUB_ID))
			.andExpect(jsonPath("$.items[1].productName").value(nameOf(ACTIVE_HUB_ID)))
			.andExpect(jsonPath("$.items[1].subtotal").value(hubPrice.doubleValue()));
	}

	@Test
	void persistsOrderAndItemsToTheDatabase() throws Exception {
		String body = createOrderAndReturnBody();
		Integer orderId = JsonPath.read(body, "$.id");
		String orderNumber = JsonPath.read(body, "$.orderNumber");

		assertThat(this.jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
			.isEqualTo("PENDING_PAYMENT");
		assertThat(this.jdbcTemplate.queryForObject("SELECT order_number FROM orders WHERE id = ?", String.class,
				orderId))
			.isEqualTo(orderNumber);
		assertThat(this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_item WHERE order_id = ?",
				Integer.class, orderId))
			.isEqualTo(1);
	}

	@Test
	void doesNotChangeProductStockWhenOrderIsCreated() throws Exception {
		int stockBefore = stockOf(ACTIVE_KEYBOARD_ID);

		this.mockMvc
			.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
				.content(orderJson(ACTIVE_KEYBOARD_ID, 3)))
			.andExpect(status().isCreated());

		assertThat(stockOf(ACTIVE_KEYBOARD_ID)).isEqualTo(stockBefore);
	}

	@Test
	void allowsOrderingBeyondAvailableStockBecauseStockIsReservedAtPayment() throws Exception {
		int stock = stockOf(ACTIVE_KEYBOARD_ID);

		this.mockMvc
			.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
				.content(orderJson(ACTIVE_KEYBOARD_ID, stock + 100)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.items[0].quantity").value(stock + 100));

		assertThat(stockOf(ACTIVE_KEYBOARD_ID)).isEqualTo(stock);
	}

	@Test
	void ordersGetDistinctOrderNumbers() throws Exception {
		String first = JsonPath.read(createOrderAndReturnBody(), "$.orderNumber");
		String second = JsonPath.read(createOrderAndReturnBody(), "$.orderNumber");

		assertThat(first).isNotEqualTo(second);
	}

	// --- 조회 ---------------------------------------------------------------

	@Test
	void listsOrdersWithTheirItems() throws Exception {
		createOrderAndReturnBody();

		this.mockMvc.perform(get("/api/orders"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
			.andExpect(jsonPath("$[0].orderNumber", startsWith("ORD-")))
			.andExpect(jsonPath("$[0].status").value("PENDING_PAYMENT"))
			.andExpect(jsonPath("$[0].items", hasSize(greaterThanOrEqualTo(1))))
			.andExpect(jsonPath("$[0].items[0].productName").isString());
	}

	@Test
	void listDoesNotDuplicateOrdersThatHaveSeveralItems() throws Exception {
		this.mockMvc
			.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
				.content(orderJson(ACTIVE_KEYBOARD_ID, 1, ACTIVE_HUB_ID, 1)))
			.andExpect(status().isCreated());

		String body = this.mockMvc.perform(get("/api/orders"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		List<String> orderNumbers = JsonPath.read(body, "$[*].orderNumber");
		assertThat(orderNumbers).doesNotHaveDuplicates().hasSize(countOf("orders"));
	}

	@Test
	void returnsSingleOrderWithItems() throws Exception {
		Integer orderId = JsonPath.read(createOrderAndReturnBody(), "$.id");

		this.mockMvc.perform(get("/api/orders/{orderId}", orderId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(orderId))
			.andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].productId").value((int) ACTIVE_KEYBOARD_ID));
	}

	@Test
	void returnsNotFoundForUnknownOrder() throws Exception {
		this.mockMvc.perform(get("/api/orders/{orderId}", 999999L))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
			.andExpect(jsonPath("$.path").value("/api/orders/999999"))
			.andExpect(jsonPath("$.fieldErrors", hasSize(0)))
			.andExpect(jsonPath("$.timestamp").isString())
			.andExpect(jsonPath("$.trace").doesNotExist())
			.andExpect(jsonPath("$.stackTrace").doesNotExist());
	}

	// --- Business Rule 위반 -------------------------------------------------

	@Test
	void rejectsDuplicateProductWithBadRequest() throws Exception {
		expectRejected(orderJson(ACTIVE_KEYBOARD_ID, 1, ACTIVE_KEYBOARD_ID, 2), 400, "DUPLICATE_PRODUCT");
	}

	@Test
	void rejectsUnknownProductWithNotFound() throws Exception {
		expectRejected(orderJson(UNKNOWN_PRODUCT_ID, 1), 404, "PRODUCT_NOT_FOUND");
	}

	@Test
	void rejectsInactiveProductWithConflict() throws Exception {
		assertThat(statusOf(INACTIVE_PRODUCT_ID)).isEqualTo("INACTIVE");

		expectRejected(orderJson(INACTIVE_PRODUCT_ID, 1), 409, "PRODUCT_INACTIVE");
	}

	// --- Validation 실패 ----------------------------------------------------

	@Test
	void rejectsEmptyItemList() throws Exception {
		expectRejected("{\"items\":[]}", 400, "VALIDATION_ERROR");
	}

	@Test
	void rejectsMissingItemList() throws Exception {
		expectRejected("{}", 400, "VALIDATION_ERROR");
	}

	@Test
	void rejectsZeroQuantity() throws Exception {
		expectRejected(orderJson(ACTIVE_KEYBOARD_ID, 0), 400, "VALIDATION_ERROR");
	}

	@Test
	void rejectsNegativeQuantity() throws Exception {
		expectRejected(orderJson(ACTIVE_KEYBOARD_ID, -1), 400, "VALIDATION_ERROR");
	}

	@Test
	void rejectsNullProductId() throws Exception {
		expectRejected("{\"items\":[{\"productId\":null,\"quantity\":1}]}", 400, "VALIDATION_ERROR");
	}

	@Test
	void rejectsNullQuantity() throws Exception {
		expectRejected("{\"items\":[{\"productId\":1,\"quantity\":null}]}", 400, "VALIDATION_ERROR");
	}

	@Test
	void validationFailureNamesTheOffendingField() throws Exception {
		String body = this.mockMvc
			.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
				.content(orderJson(ACTIVE_KEYBOARD_ID, 0)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.fieldErrors", hasSize(greaterThanOrEqualTo(1))))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).contains("items[0].quantity");
	}

	// --- 원자성 -------------------------------------------------------------

	/**
	 * 두 번째 상품이 판매 중지, 미존재, 중복인 경우처럼 요청이 도중에 실패하면 아무 문제 없던
	 * 첫 번째 상품에 대해서도 주문과 주문 항목이 남아 있어서는 안 된다.
	 */
	@Test
	void doesNotPartiallyPersistOrderWhenRequestIsRejected() throws Exception {
		int ordersBefore = countOf("orders");
		int itemsBefore = countOf("order_item");

		expectRejected(orderJson(ACTIVE_KEYBOARD_ID, 1, INACTIVE_PRODUCT_ID, 1), 409, "PRODUCT_INACTIVE");
		expectRejected(orderJson(ACTIVE_KEYBOARD_ID, 1, UNKNOWN_PRODUCT_ID, 1), 404, "PRODUCT_NOT_FOUND");
		expectRejected(orderJson(ACTIVE_KEYBOARD_ID, 1, ACTIVE_KEYBOARD_ID, 1), 400, "DUPLICATE_PRODUCT");
		expectRejected(orderJson(ACTIVE_KEYBOARD_ID, 0), 400, "VALIDATION_ERROR");

		assertThat(countOf("orders")).isEqualTo(ordersBefore);
		assertThat(countOf("order_item")).isEqualTo(itemsBefore);
	}

	// --- 헬퍼 ---------------------------------------------------------------

	private void expectRejected(String requestBody, int expectedStatus, String expectedCode) throws Exception {
		this.mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(requestBody))
			.andExpect(status().is(expectedStatus))
			.andExpect(jsonPath("$.status").value(expectedStatus))
			.andExpect(jsonPath("$.code").value(expectedCode))
			.andExpect(jsonPath("$.path").value("/api/orders"))
			.andExpect(jsonPath("$.timestamp").isString())
			.andExpect(jsonPath("$.trace").doesNotExist())
			.andExpect(jsonPath("$.stackTrace").doesNotExist());
	}

	private String createOrderAndReturnBody() throws Exception {
		return this.mockMvc
			.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
				.content(orderJson(ACTIVE_KEYBOARD_ID, 1)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private String orderJson(long productId, int quantity) {
		return "{\"items\":[{\"productId\":%d,\"quantity\":%d}]}".formatted(productId, quantity);
	}

	private String orderJson(long firstProductId, int firstQuantity, long secondProductId, int secondQuantity) {
		return "{\"items\":[{\"productId\":%d,\"quantity\":%d},{\"productId\":%d,\"quantity\":%d}]}"
			.formatted(firstProductId, firstQuantity, secondProductId, secondQuantity);
	}

	private Integer tableExists(String tableName) {
		return this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
				Integer.class, tableName);
	}

	private List<String> constraintsOf(String tableName) {
		return this.jdbcTemplate.queryForList(
				"SELECT constraint_name FROM information_schema.table_constraints "
						+ "WHERE table_schema = DATABASE() AND table_name = ?",
				String.class, tableName);
	}

	private int countOf(String tableName) {
		return this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
	}

	private BigDecimal priceOf(long productId) {
		return this.jdbcTemplate.queryForObject("SELECT price FROM product WHERE id = ?", BigDecimal.class,
				productId);
	}

	private String nameOf(long productId) {
		return this.jdbcTemplate.queryForObject("SELECT name FROM product WHERE id = ?", String.class, productId);
	}

	private String statusOf(long productId) {
		return this.jdbcTemplate.queryForObject("SELECT status FROM product WHERE id = ?", String.class, productId);
	}

	private int stockOf(long productId) {
		return this.jdbcTemplate.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Integer.class,
				productId);
	}

}

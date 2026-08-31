package com.pulseops.demoshop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseops.demoshop.TestcontainersConfiguration;
import java.time.Instant;
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
 * 실제 MySQL 8.4 Container를 대상으로 Product API를 검증한다. Flyway Migration과 JPA Mapping을
 * 실제 실행 환경과 동일하게 확인한다.
 * Docker가 필요하며 Docker를 사용할 수 없으면 자동으로 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "pulseops.telemetry.enabled=false")
class ProductApiIntegrationTest {

	private static final int SEEDED_PRODUCT_COUNT = 10;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void flywayAppliesSchemaMigration() {
		Integer applied = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1", Integer.class);
		assertThat(applied).isEqualTo(1);

		Integer productTable = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'product'",
				Integer.class);
		assertThat(productTable).isEqualTo(1);

		assertThat(this.jdbcTemplate.queryForList(
				"SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product'",
				String.class))
			.containsExactlyInAnyOrder("id", "name", "description", "price", "stock_quantity", "status", "created_at",
					"updated_at");
	}

	@Test
	void flywayAppliesSeedMigration() {
		Integer applied = this.jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = 1", Integer.class);
		assertThat(applied).isEqualTo(1);

		Integer seeded = this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product", Integer.class);
		assertThat(seeded).isEqualTo(SEEDED_PRODUCT_COUNT);
	}

	@Test
	void productTableRejectsNegativePriceAndStock() {
		assertThat(this.jdbcTemplate.queryForList(
				"SELECT constraint_name FROM information_schema.table_constraints "
						+ "WHERE table_schema = DATABASE() AND table_name = 'product' AND constraint_type = 'CHECK'",
				String.class))
			.contains("ck_product_price", "ck_product_stock_quantity");
	}

	@Test
	void getProductsReturnsSeededProducts() throws Exception {
		this.mockMvc.perform(get("/api/products"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$", hasSize(SEEDED_PRODUCT_COUNT)))
			.andExpect(jsonPath("$[0].id").isNumber())
			.andExpect(jsonPath("$[0].name").isString())
			.andExpect(jsonPath("$[0].price").isNumber())
			.andExpect(jsonPath("$[0].stockQuantity").isNumber())
			.andExpect(jsonPath("$[0].status").value("ACTIVE"))
			// 목록용 DTO는 선언하지 않은 필드를 노출해서는 안 된다
			.andExpect(jsonPath("$[0].description").doesNotExist())
			.andExpect(jsonPath("$[0].createdAt").doesNotExist())
			.andExpect(jsonPath("$[0].updatedAt").doesNotExist());
	}

	@Test
	void getProductReturnsDetailForExistingProduct() throws Exception {
		Long productId = this.jdbcTemplate.queryForObject("SELECT MIN(id) FROM product", Long.class);

		this.mockMvc.perform(get("/api/products/{productId}", productId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(productId))
			.andExpect(jsonPath("$.name").value("Mechanical Keyboard 87-Key"))
			.andExpect(jsonPath("$.description").isString())
			.andExpect(jsonPath("$.price").value(129000.00))
			.andExpect(jsonPath("$.stockQuantity").value(42))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.createdAt").doesNotExist())
			.andExpect(jsonPath("$.updatedAt").doesNotExist());
	}

	@Test
	void getProductReturnsNotFoundForUnknownProduct() throws Exception {
		String body = this.mockMvc.perform(get("/api/products/{productId}", 999999L))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("상품을 찾을 수 없습니다."))
			.andExpect(jsonPath("$.path").value("/api/products/999999"))
			.andExpect(jsonPath("$.fieldErrors", hasSize(0)))
			.andExpect(jsonPath("$.timestamp").isString())
			// Stack Trace가 클라이언트에 노출되어서는 안 된다
			.andExpect(jsonPath("$.trace").doesNotExist())
			.andExpect(jsonPath("$.stackTrace").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).doesNotContain("com.pulseops.demoshop.product.ProductNotFoundException");
	}

	@Test
	void errorTimestampIsIso8601Instant() throws Exception {
		String body = this.mockMvc.perform(get("/api/products/{productId}", 999999L))
			.andExpect(status().isNotFound())
			.andReturn()
			.getResponse()
			.getContentAsString();

		String timestamp = com.jayway.jsonpath.JsonPath.read(body, "$.timestamp");
		assertThat(Instant.parse(timestamp)).isNotNull();
	}

}

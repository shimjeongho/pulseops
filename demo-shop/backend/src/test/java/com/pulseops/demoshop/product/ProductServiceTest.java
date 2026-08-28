package com.pulseops.demoshop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ProductServiceTest {

	private ProductRepository productRepository;

	private ProductService productService;

	@BeforeEach
	void setUp() {
		this.productRepository = mock(ProductRepository.class);
		this.productService = new ProductService(this.productRepository);
	}

	@Test
	void getByIdReturnsProductWhenItExists() {
		Product product = product(1L, "Mechanical Keyboard 87-Key", new BigDecimal("129000.00"), 42);
		given(this.productRepository.findById(1L)).willReturn(Optional.of(product));

		Product found = this.productService.getById(1L);

		assertThat(found.getId()).isEqualTo(1L);
		assertThat(found.getName()).isEqualTo("Mechanical Keyboard 87-Key");
		assertThat(found.getPrice()).isEqualByComparingTo("129000.00");
		assertThat(found.getStockQuantity()).isEqualTo(42);
		assertThat(found.getStatus()).isEqualTo(ProductStatus.ACTIVE);
	}

	@Test
	void getByIdThrowsProductNotFoundExceptionWhenMissing() {
		given(this.productRepository.findById(999L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.productService.getById(999L))
			.isInstanceOf(ProductNotFoundException.class)
			.extracting("productId")
			.isEqualTo(999L);
	}

	@Test
	void findAllReturnsEveryProduct() {
		given(this.productRepository.findAll())
			.willReturn(List.of(product(1L, "Wireless Ergonomic Mouse", new BigDecimal("45900.00"), 130),
					product(2L, "USB-C 7-in-1 Hub", new BigDecimal("59000.00"), 78)));

		List<Product> products = this.productService.findAll();

		assertThat(products).hasSize(2).extracting(Product::getName)
			.containsExactly("Wireless Ergonomic Mouse", "USB-C 7-in-1 Hub");
	}

	private Product product(Long id, String name, BigDecimal price, int stockQuantity) {
		Product product = new Product(name, "description", price, stockQuantity, ProductStatus.ACTIVE);
		ReflectionTestUtils.setField(product, "id", id);
		return product;
	}

}

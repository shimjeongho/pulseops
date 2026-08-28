package com.pulseops.demoshop.product;

import com.pulseops.demoshop.product.dto.ProductDetailResponse;
import com.pulseops.demoshop.product.dto.ProductSummaryResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

	private final ProductService productService;

	public ProductController(ProductService productService) {
		this.productService = productService;
	}

	@GetMapping
	public List<ProductSummaryResponse> getProducts() {
		return this.productService.findAll().stream().map(ProductSummaryResponse::from).toList();
	}

	@GetMapping("/{productId}")
	public ProductDetailResponse getProduct(@PathVariable Long productId) {
		return ProductDetailResponse.from(this.productService.getById(productId));
	}

}

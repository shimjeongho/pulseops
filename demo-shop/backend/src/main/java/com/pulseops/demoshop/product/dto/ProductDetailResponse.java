package com.pulseops.demoshop.product.dto;

import com.pulseops.demoshop.product.Product;
import com.pulseops.demoshop.product.ProductStatus;
import java.math.BigDecimal;

/**
 * Product representation used by the single-product endpoint.
 */
public record ProductDetailResponse(Long id, String name, String description, BigDecimal price, int stockQuantity,
		ProductStatus status) {

	public static ProductDetailResponse from(Product product) {
		return new ProductDetailResponse(product.getId(), product.getName(), product.getDescription(),
				product.getPrice(), product.getStockQuantity(), product.getStatus());
	}

}

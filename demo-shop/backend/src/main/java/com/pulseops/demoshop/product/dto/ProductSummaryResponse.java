package com.pulseops.demoshop.product.dto;

import com.pulseops.demoshop.product.Product;
import com.pulseops.demoshop.product.ProductStatus;
import java.math.BigDecimal;

/**
 * Product representation used by the list endpoint.
 */
public record ProductSummaryResponse(Long id, String name, BigDecimal price, int stockQuantity,
		ProductStatus status) {

	public static ProductSummaryResponse from(Product product) {
		return new ProductSummaryResponse(product.getId(), product.getName(), product.getPrice(),
				product.getStockQuantity(), product.getStatus());
	}

}

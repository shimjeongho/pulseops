package com.pulseops.demoshop.product.dto;

import com.pulseops.demoshop.product.Product;
import com.pulseops.demoshop.product.ProductStatus;
import java.math.BigDecimal;

/**
 * 상품 목록 조회 Endpoint에서 사용하는 상품 표현이다.
 */
public record ProductSummaryResponse(Long id, String name, BigDecimal price, int stockQuantity,
		ProductStatus status) {

	public static ProductSummaryResponse from(Product product) {
		return new ProductSummaryResponse(product.getId(), product.getName(), product.getPrice(),
				product.getStockQuantity(), product.getStatus());
	}

}

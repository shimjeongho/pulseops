package com.pulseops.demoshop.product.dto;

import com.pulseops.demoshop.product.Product;
import com.pulseops.demoshop.product.ProductStatus;
import java.math.BigDecimal;

/**
 * 상품 단건 조회 Endpoint에서 사용하는 상품 표현이다.
 */
public record ProductDetailResponse(Long id, String name, String description, BigDecimal price, int stockQuantity,
		ProductStatus status) {

	public static ProductDetailResponse from(Product product) {
		return new ProductDetailResponse(product.getId(), product.getName(), product.getDescription(),
				product.getPrice(), product.getStockQuantity(), product.getStatus());
	}

}

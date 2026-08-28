package com.pulseops.demoshop.product;

/**
 * Sale status of a product. Sold-out is not a status: it is derived from
 * {@code stockQuantity == 0}.
 */
public enum ProductStatus {

	ACTIVE,
	INACTIVE

}

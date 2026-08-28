package com.pulseops.demoshop.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single line of an order. The product is referenced by id only and its name and
 * price are copied in, so later changes to the product do not rewrite past orders.
 */
@Entity
@Table(name = "order_item")
public class OrderItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false)
	private Order order;

	@Column(name = "product_id", nullable = false)
	private Long productId;

	@Column(name = "product_name", nullable = false, length = 120)
	private String productName;

	@Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
	private BigDecimal unitPrice;

	@Column(name = "quantity", nullable = false)
	private int quantity;

	@Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
	private BigDecimal subtotal;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	protected OrderItem() {
		// for JPA
	}

	public OrderItem(Long productId, String productName, BigDecimal unitPrice, int quantity) {
		this.productId = productId;
		this.productName = productName;
		this.unitPrice = unitPrice;
		this.quantity = quantity;
		this.subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
	}

	@PrePersist
	void onCreate() {
		this.createdAt = LocalDateTime.now();
	}

	void assignTo(Order order) {
		this.order = order;
	}

	public Long getId() {
		return this.id;
	}

	public Order getOrder() {
		return this.order;
	}

	public Long getProductId() {
		return this.productId;
	}

	public String getProductName() {
		return this.productName;
	}

	public BigDecimal getUnitPrice() {
		return this.unitPrice;
	}

	public int getQuantity() {
		return this.quantity;
	}

	public BigDecimal getSubtotal() {
		return this.subtotal;
	}

	public LocalDateTime getCreatedAt() {
		return this.createdAt;
	}

}

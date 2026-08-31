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
 * 주문의 단일 항목이다. 상품은 ID로만 참조하고 이름과 가격은 주문 시점의 값을 복사해 두므로
 * 이후 상품 정보가 변경되어도 과거 주문 내역은 바뀌지 않는다.
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
		// JPA 전용 기본 생성자
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

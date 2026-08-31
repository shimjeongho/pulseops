package com.pulseops.demoshop.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Demo Shop에서 판매하는 상품이다. Schema는 Flyway가 관리하며, 이 Entity는 기동 시점에
 * Schema와 일치하는지 검증된다(spring.jpa.hibernate.ddl-auto=validate).
 */
@Entity
@Table(name = "product")
public class Product {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "name", nullable = false, length = 120)
	private String name;

	@Column(name = "description", length = 1000)
	private String description;

	@Column(name = "price", nullable = false, precision = 12, scale = 2)
	private BigDecimal price;

	@Column(name = "stock_quantity", nullable = false)
	private int stockQuantity;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ProductStatus status;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	protected Product() {
		// JPA 전용 기본 생성자
	}

	public Product(String name, String description, BigDecimal price, int stockQuantity, ProductStatus status) {
		this.name = name;
		this.description = description;
		this.price = price;
		this.stockQuantity = stockQuantity;
		this.status = status;
	}

	/**
	 * 요청한 수량을 감당할 재고가 있는지 확인한다. 호출자는 어떤 재고도 차감하기 전에 모든 상품을
	 * 먼저 검사하므로, 마지막 항목이 부족해도 앞선 상품의 재고가 이미 차감되어 있지 않다.
	 */
	public boolean hasStock(int quantity) {
		return this.stockQuantity >= quantity;
	}

	/**
	 * 재고에서 {@code quantity}만큼 차감한다. 재고 감소는 항상 이 Method를 통해서만 이루어지므로
	 * 호출자가 어떻게 작성되든 재고 수량이 음수가 될 수 없다.
	 */
	public void decreaseStock(int quantity) {
		if (quantity <= 0) {
			throw new IllegalArgumentException("Stock decrease must be positive: " + quantity);
		}
		if (!hasStock(quantity)) {
			throw new InsufficientStockException(this.id, this.name, quantity, this.stockQuantity);
		}
		this.stockQuantity -= quantity;
	}

	@PrePersist
	void onCreate() {
		LocalDateTime now = LocalDateTime.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = LocalDateTime.now();
	}

	public Long getId() {
		return this.id;
	}

	public String getName() {
		return this.name;
	}

	public String getDescription() {
		return this.description;
	}

	public BigDecimal getPrice() {
		return this.price;
	}

	public int getStockQuantity() {
		return this.stockQuantity;
	}

	public ProductStatus getStatus() {
		return this.status;
	}

	public LocalDateTime getCreatedAt() {
		return this.createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return this.updatedAt;
	}

}

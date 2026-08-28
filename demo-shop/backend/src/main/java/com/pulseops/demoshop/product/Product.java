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
 * A product on sale in the demo shop. The schema is owned by Flyway; this entity is
 * validated against it at startup (spring.jpa.hibernate.ddl-auto=validate).
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
		// for JPA
	}

	public Product(String name, String description, BigDecimal price, int stockQuantity, ProductStatus status) {
		this.name = name;
		this.description = description;
		this.price = price;
		this.stockQuantity = stockQuantity;
		this.status = status;
	}

	/**
	 * Whether this product can cover the requested quantity. Callers check every
	 * product before decreasing any, so a shortage on the last line does not leave the
	 * earlier ones already deducted.
	 */
	public boolean hasStock(int quantity) {
		return this.stockQuantity >= quantity;
	}

	/**
	 * Takes {@code quantity} off the shelf. Stock is only ever reduced through this
	 * method, so the quantity can never go negative however the caller is written.
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

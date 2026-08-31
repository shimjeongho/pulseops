package com.pulseops.demoshop.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 고객의 주문이다. Order가 Aggregate Root이며 OrderItem은 Order와 생명주기를 함께한다.
 * 총 금액은 클라이언트가 보낸 값이 아니라 각 항목의 소계를 합산해 계산한다.
 * Schema는 Flyway가 관리한다.
 */
@Entity
@Table(name = "orders")
public class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "order_number", nullable = false, length = 40, unique = true)
	private String orderNumber;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private OrderStatus status;

	@Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
	private BigDecimal totalAmount;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id")
	private List<OrderItem> items = new ArrayList<>();

	protected Order() {
		// JPA 전용 기본 생성자
	}

	public Order(String orderNumber, List<OrderItem> items) {
		this.orderNumber = orderNumber;
		this.status = OrderStatus.PENDING_PAYMENT;
		items.forEach(this::addItem);
		this.totalAmount = calculateTotalAmount();
	}

	private void addItem(OrderItem item) {
		this.items.add(item);
		item.assignTo(this);
	}

	private BigDecimal calculateTotalAmount() {
		return this.items.stream().map(OrderItem::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	/**
	 * 주문을 결제 완료 상태로 기록한다. 결제 대기 상태인 주문만 전이할 수 있으므로
	 * 두 번째 결제가 앞선 결제 결과를 조용히 덮어쓰는 일이 발생하지 않는다.
	 */
	public void markPaid() {
		if (this.status != OrderStatus.PENDING_PAYMENT) {
			throw new OrderAlreadyPaidException(this.id);
		}
		this.status = OrderStatus.PAID;
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

	public String getOrderNumber() {
		return this.orderNumber;
	}

	public OrderStatus getStatus() {
		return this.status;
	}

	public BigDecimal getTotalAmount() {
		return this.totalAmount;
	}

	public LocalDateTime getCreatedAt() {
		return this.createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return this.updatedAt;
	}

	public List<OrderItem> getItems() {
		return Collections.unmodifiableList(this.items);
	}

}

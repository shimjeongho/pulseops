package com.pulseops.demoshop.payment;

import com.pulseops.demoshop.order.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 주문에 대한 가상 결제 시도이다. 하나의 주문에 여러 번의 시도가 있을 수 있으므로 주문 참조에
 * Unique 제약을 두지 않고 Order 쪽에도 결제 Collection을 두지 않는다.
 * Schema는 Flyway가 관리한다.
 */
@Entity
@Table(name = "payment")
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "payment_number", nullable = false, length = 40, unique = true)
	private String paymentNumber;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false)
	private Order order;

	@Column(name = "amount", nullable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PaymentStatus status;

	@Column(name = "failure_code", length = 50)
	private String failureCode;

	@Column(name = "failure_message", length = 255)
	private String failureMessage;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	protected Payment() {
		// JPA 전용 기본 생성자
	}

	/**
	 * 주문 전체에 대해 완료된 결제를 만든다. 결제 금액은 호출자가 아니라 주문에서 가져오므로
	 * 이후 상품 가격이 변경되더라도 주문 당시 금액과 어긋나지 않는다.
	 */
	public static Payment success(String paymentNumber, Order order) {
		Payment payment = new Payment();
		payment.paymentNumber = paymentNumber;
		payment.order = order;
		payment.amount = order.getTotalAmount();
		payment.status = PaymentStatus.SUCCESS;
		return payment;
	}

	@PrePersist
	void onCreate() {
		this.createdAt = LocalDateTime.now();
	}

	public Long getId() {
		return this.id;
	}

	public String getPaymentNumber() {
		return this.paymentNumber;
	}

	public Order getOrder() {
		return this.order;
	}

	public BigDecimal getAmount() {
		return this.amount;
	}

	public PaymentStatus getStatus() {
		return this.status;
	}

	public String getFailureCode() {
		return this.failureCode;
	}

	public String getFailureMessage() {
		return this.failureMessage;
	}

	public LocalDateTime getCreatedAt() {
		return this.createdAt;
	}

}

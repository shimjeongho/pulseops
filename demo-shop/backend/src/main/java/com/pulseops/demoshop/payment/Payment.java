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
 * A virtual payment attempt against an order. Several attempts may point at one order,
 * so the order is referenced without a unique constraint and without a collection on
 * the order side. The schema is owned by Flyway.
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
		// for JPA
	}

	/**
	 * A settled payment for the whole of an order. The amount is taken from the order
	 * rather than from the caller, so it cannot drift from what was ordered even if the
	 * product prices have since changed.
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

package com.pulseops.demoshop.payment;

import com.pulseops.demoshop.payment.dto.PaymentCreateRequest;
import com.pulseops.demoshop.payment.dto.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

	private final PaymentService paymentService;

	public PaymentController(PaymentService paymentService) {
		this.paymentService = paymentService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public PaymentResponse createPayment(@Valid @RequestBody PaymentCreateRequest request) {
		return PaymentResponse.from(this.paymentService.pay(request));
	}

}

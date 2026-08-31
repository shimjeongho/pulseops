package com.pulseops.demoshop.common.exception;

import com.pulseops.demoshop.order.DuplicateProductException;
import com.pulseops.demoshop.order.OrderAlreadyPaidException;
import com.pulseops.demoshop.order.OrderNotFoundException;
import com.pulseops.demoshop.product.InsufficientStockException;
import com.pulseops.demoshop.product.ProductInactiveException;
import com.pulseops.demoshop.product.ProductNotFoundException;
import com.pulseops.demoshop.telemetry.TelemetryAttributes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Business Exception을 공통 오류 응답 본문으로 변환한다. 따라서 Controller는 HTTP Status를
 * 매핑하기 위해 Business Exception을 직접 처리하지 않는다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ProductNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleProductNotFound(ProductNotFoundException exception,
			HttpServletRequest request) {
		return build(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다.", request);
	}

	@ExceptionHandler(ProductInactiveException.class)
	public ResponseEntity<ErrorResponse> handleProductInactive(ProductInactiveException exception,
			HttpServletRequest request) {
		return build(HttpStatus.CONFLICT, "PRODUCT_INACTIVE", "판매 중인 상품이 아닙니다.", request);
	}

	@ExceptionHandler(OrderNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleOrderNotFound(OrderNotFoundException exception,
			HttpServletRequest request) {
		return build(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.", request);
	}

	@ExceptionHandler(OrderAlreadyPaidException.class)
	public ResponseEntity<ErrorResponse> handleOrderAlreadyPaid(OrderAlreadyPaidException exception,
			HttpServletRequest request) {
		return build(HttpStatus.CONFLICT, "ORDER_ALREADY_PAID", "이미 결제가 완료된 주문입니다.", request);
	}

	@ExceptionHandler(InsufficientStockException.class)
	public ResponseEntity<ErrorResponse> handleInsufficientStock(InsufficientStockException exception,
			HttpServletRequest request) {
		return build(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
				"'" + exception.getProductName() + "' 상품의 재고가 부족합니다.", request);
	}

	@ExceptionHandler(DuplicateProductException.class)
	public ResponseEntity<ErrorResponse> handleDuplicateProduct(DuplicateProductException exception,
			HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, "DUPLICATE_PRODUCT", "같은 상품을 중복해서 주문할 수 없습니다.", request);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationFailure(MethodArgumentNotValidException exception,
			HttpServletRequest request) {
		List<String> fieldErrors = exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map((error) -> error.getField() + ": " + error.getDefaultMessage())
			.sorted(Comparator.naturalOrder())
			.toList();
		ErrorResponse body = ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), "VALIDATION_ERROR",
				"요청 값이 올바르지 않습니다.", request.getRequestURI(), fieldErrors);
		TelemetryAttributes.setError(request, body.code(), body.message());
		return ResponseEntity.badRequest().body(body);
	}

	/**
	 * 이 Class가 확정한 Error Code와 message를 Telemetry가 그대로 읽어 갈 수 있도록 Request에
	 * 남긴다. 응답 본문은 달라지지 않으며 HTTP 오류 매핑의 소유자도 계속 이 Class 하나다.
	 */
	private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
			HttpServletRequest request) {
		TelemetryAttributes.setError(request, code, message);
		return ResponseEntity.status(status)
			.body(ErrorResponse.of(status.value(), code, message, request.getRequestURI()));
	}

}

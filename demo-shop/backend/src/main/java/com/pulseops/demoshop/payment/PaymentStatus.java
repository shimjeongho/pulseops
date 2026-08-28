package com.pulseops.demoshop.payment;

/**
 * Outcome of a payment attempt. A payment row is only written once the attempt has an
 * outcome, so there is no in-flight state to represent.
 */
public enum PaymentStatus {

	SUCCESS,
	FAILED

}

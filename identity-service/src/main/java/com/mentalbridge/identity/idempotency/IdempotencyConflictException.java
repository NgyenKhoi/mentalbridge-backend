package com.mentalbridge.identity.idempotency;

public class IdempotencyConflictException extends RuntimeException {

	public IdempotencyConflictException() {
		super("Idempotency key conflicts with an earlier request");
	}

}

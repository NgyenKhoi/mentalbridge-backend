package com.mentalbridge.identity.account;

public class InvalidStateTransitionException extends RuntimeException {
	public InvalidStateTransitionException(String message) {
		super(message);
	}
}

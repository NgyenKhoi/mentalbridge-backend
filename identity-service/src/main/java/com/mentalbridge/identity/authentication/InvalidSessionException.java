package com.mentalbridge.identity.authentication;

public class InvalidSessionException extends RuntimeException {

	public InvalidSessionException() {
		super("Session is invalid");
	}

}

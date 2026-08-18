package com.mentalbridge.identity.authentication;

public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("Credentials are invalid");
	}

}

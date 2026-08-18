package com.mentalbridge.identity.registration;

public class InvalidVerificationChallengeException extends RuntimeException {

	public InvalidVerificationChallengeException() {
		super("Verification challenge is invalid");
	}

}

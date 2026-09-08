package com.mentalbridge.identity.credential;

public class CredentialRateLimitException extends RuntimeException {

	private final long retryAfterSeconds;

	public CredentialRateLimitException(long retryAfterSeconds) {
		super("Credential request rate limit exceeded");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}
}

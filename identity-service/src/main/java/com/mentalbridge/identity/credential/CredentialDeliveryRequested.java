package com.mentalbridge.identity.credential;

import java.util.UUID;

public record CredentialDeliveryRequested(UUID accountId, String normalizedEmail, String challenge, Purpose purpose,
		UUID correlationId) {

	public enum Purpose {
		VERIFY_EMAIL,
		RESET_PASSWORD
	}

	@Override
	public String toString() {
		return "CredentialDeliveryRequested[accountId=" + accountId
				+ ", normalizedEmail=[REDACTED], challenge=[REDACTED], purpose=" + purpose
				+ ", correlationId=" + correlationId + "]";
	}
}

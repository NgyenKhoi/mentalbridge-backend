package com.mentalbridge.identity.registration;

import java.util.UUID;

public record RegistrationRequested(UUID accountId, String normalizedEmail, String challenge, UUID correlationId) {

	@Override
	public String toString() {
		return "RegistrationRequested[accountId=" + accountId + ", normalizedEmail=[REDACTED], challenge=[REDACTED], correlationId="
				+ correlationId + "]";
	}
}

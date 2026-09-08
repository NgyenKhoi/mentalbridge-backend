package com.mentalbridge.identity.registration;

import java.util.UUID;

public interface VerificationDelivery {

	void requestDelivery(UUID accountId, String normalizedEmail, String challenge, UUID correlationId);

	default void requestEmailVerification(UUID accountId, String normalizedEmail, String challenge, UUID correlationId) {
		requestDelivery(accountId, normalizedEmail, challenge, correlationId);
	}

	default void requestPasswordRecovery(UUID accountId, String normalizedEmail, String challenge,
			UUID correlationId) {
		throw new UnsupportedOperationException("Password recovery delivery is not configured");
	}

}

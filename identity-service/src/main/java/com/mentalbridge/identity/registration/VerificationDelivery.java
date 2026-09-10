package com.mentalbridge.identity.registration;

import java.util.UUID;

public interface VerificationDelivery {

	void requestEmailVerification(UUID accountId, String normalizedEmail, String challenge, UUID correlationId);

	void requestPasswordRecovery(UUID accountId, String normalizedEmail, String challenge, UUID correlationId);

}

package com.mentalbridge.identity.registration;

import java.util.UUID;

public interface VerificationDelivery {

	void requestDelivery(UUID accountId, String normalizedEmail, String challenge, UUID correlationId);

}

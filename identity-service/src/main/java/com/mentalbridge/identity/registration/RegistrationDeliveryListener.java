package com.mentalbridge.identity.registration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RegistrationDeliveryListener {

	private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationDeliveryListener.class);

	private final VerificationDelivery delivery;

	public RegistrationDeliveryListener(VerificationDelivery delivery) {
		this.delivery = delivery;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void deliver(RegistrationRequested registration) {
		try {
			delivery.requestEmailVerification(registration.accountId(), registration.normalizedEmail(), registration.challenge(),
					registration.correlationId());
		}
		catch (RuntimeException exception) {
			LOGGER.error("Verification delivery failed accountId={} correlationId={}", registration.accountId(),
					registration.correlationId());
		}
	}

}

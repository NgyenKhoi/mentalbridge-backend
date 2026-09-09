package com.mentalbridge.identity.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.mentalbridge.identity.registration.VerificationDelivery;

@Component
public class CredentialDeliveryListener {

	private static final Logger LOGGER = LoggerFactory.getLogger(CredentialDeliveryListener.class);

	private final VerificationDelivery delivery;

	public CredentialDeliveryListener(VerificationDelivery delivery) {
		this.delivery = delivery;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void deliver(CredentialDeliveryRequested requested) {
		try {
			if (requested.purpose() == CredentialDeliveryRequested.Purpose.VERIFY_EMAIL) {
				delivery.requestEmailVerification(requested.accountId(), requested.normalizedEmail(),
						requested.challenge(), requested.correlationId());
			}
			else {
				delivery.requestPasswordRecovery(requested.accountId(), requested.normalizedEmail(),
						requested.challenge(), requested.correlationId());
			}
		}
		catch (RuntimeException exception) {
			LOGGER.error("Credential delivery failed accountId={} purpose={} correlationId={}", requested.accountId(),
					requested.purpose(), requested.correlationId());
		}
	}
}

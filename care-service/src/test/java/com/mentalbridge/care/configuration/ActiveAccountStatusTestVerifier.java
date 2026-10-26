package com.mentalbridge.care.configuration;

import java.util.UUID;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
class ActiveAccountStatusTestVerifier extends IdentityAccountStatusVerifier {
	ActiveAccountStatusTestVerifier(IdentityAccountStatusHttpClient client, IdentityAccountStatusProperties properties) {
		super(client, properties);
	}

	@Override
	public boolean isActive(UUID accountId, UUID correlationId) {
		return true;
	}
}

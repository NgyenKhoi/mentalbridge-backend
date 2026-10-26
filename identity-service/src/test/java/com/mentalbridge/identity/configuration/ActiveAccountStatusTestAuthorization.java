package com.mentalbridge.identity.configuration;

import java.util.UUID;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.mentalbridge.identity.account.AccountRepository;
import com.mentalbridge.identity.account.AccountStatusAuthorizationService;

@Primary
@Component
class ActiveAccountStatusTestAuthorization extends AccountStatusAuthorizationService {
	ActiveAccountStatusTestAuthorization(AccountRepository accounts) {
		super(accounts);
	}

	@Override
	public boolean isActive(UUID accountId) {
		return true;
	}
}

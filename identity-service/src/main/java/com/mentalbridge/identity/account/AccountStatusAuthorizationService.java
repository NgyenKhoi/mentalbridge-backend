package com.mentalbridge.identity.account;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountStatusAuthorizationService {

	private final AccountRepository accounts;

	public AccountStatusAuthorizationService(AccountRepository accounts) {
		this.accounts = accounts;
	}

	@Transactional(readOnly = true)
	public boolean isActive(UUID accountId) {
		return accounts.existsByIdAndStatus(accountId, AccountStatus.ACTIVE);
	}
}

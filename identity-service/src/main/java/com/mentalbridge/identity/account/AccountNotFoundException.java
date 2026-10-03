package com.mentalbridge.identity.account;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {
	public AccountNotFoundException(UUID accountId) {
		super("Account was not found: " + accountId);
	}
}

package com.mentalbridge.identity.account;

import java.util.UUID;

public class DedicatedAdminProtectionException extends RuntimeException {
	public DedicatedAdminProtectionException(UUID accountId) {
		super("The dedicated administrator account cannot be changed: " + accountId);
	}
}

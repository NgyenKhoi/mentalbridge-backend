package com.mentalbridge.identity.account;

import java.util.UUID;

public class AccountVersionMismatchException extends RuntimeException {
    public AccountVersionMismatchException(UUID accountId, long expectedVersion, long actualVersion) {
        super("Account version mismatch for account " + accountId + ": expected " + expectedVersion + ", but was " + actualVersion);
    }
}

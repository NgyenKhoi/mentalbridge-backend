package com.mentalbridge.identity.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountQueryService {

	private final AccountRepository accounts;

	public AccountQueryService(AccountRepository accounts) {
		this.accounts = accounts;
	}

	@Transactional(readOnly = true)
	public Optional<AccountDetail> findById(UUID accountId) {
		return accounts.findById(accountId).filter(account -> account.status() != AccountStatus.DELETED)
				.map(account -> new AccountDetail(account.id(), account.email(), account.status(),
						account.role(),
						account.emailVerifiedAt(), account.createdAt(), account.updatedAt(), account.version()));
	}

	public record AccountDetail(UUID accountId, String email, AccountStatus status, RoleCode role,
			Instant emailVerifiedAt, Instant createdAt, Instant updatedAt, long version) {
	}

}

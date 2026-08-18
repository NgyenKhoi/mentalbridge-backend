package com.mentalbridge.identity.account;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountQueryService {

	private final AccountRepository accounts;
	private final AccountRoleRepository roles;

	public AccountQueryService(AccountRepository accounts, AccountRoleRepository roles) {
		this.accounts = accounts;
		this.roles = roles;
	}

	@Transactional(readOnly = true)
	public Optional<AccountDetail> findById(UUID accountId) {
		return accounts.findById(accountId).filter(account -> account.status() != AccountStatus.DELETED)
				.map(account -> new AccountDetail(account.id(), account.email(), account.status(),
						roles.findRoleCodes(account.id()).stream().map(RoleCode::valueOf)
								.collect(Collectors.toUnmodifiableSet()),
						account.emailVerifiedAt(), account.createdAt(), account.updatedAt(), account.version()));
	}

	public record AccountDetail(UUID accountId, String email, AccountStatus status, Set<RoleCode> roles,
			Instant emailVerifiedAt, Instant createdAt, Instant updatedAt, long version) {
	}

}

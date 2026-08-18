package com.mentalbridge.identity.account;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "account_role")
public class AccountRoleEntity {

	@EmbeddedId
	private AccountRoleId id;

	@Column(name = "granted_by")
	private UUID grantedBy;

	@Column(name = "granted_at", nullable = false, updatable = false)
	private Instant grantedAt;

	protected AccountRoleEntity() {
	}

	public AccountRoleEntity(UUID accountId, RoleCode role, Instant grantedAt) {
		this.id = new AccountRoleId(accountId, role.name());
		this.grantedAt = grantedAt;
	}

}

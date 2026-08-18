package com.mentalbridge.identity.account;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class AccountRoleId implements Serializable {

	@Column(name = "account_id", nullable = false)
	private UUID accountId;

	@Column(name = "role_code", nullable = false, length = 32)
	private String roleCode;

	protected AccountRoleId() {
	}

	public AccountRoleId(UUID accountId, String roleCode) {
		this.accountId = accountId;
		this.roleCode = roleCode;
	}

	public UUID accountId() {
		return accountId;
	}

	public String roleCode() {
		return roleCode;
	}

	@Override
	public boolean equals(Object other) {
		return this == other || other instanceof AccountRoleId that
				&& Objects.equals(accountId, that.accountId) && Objects.equals(roleCode, that.roleCode);
	}

	@Override
	public int hashCode() {
		return Objects.hash(accountId, roleCode);
	}

}

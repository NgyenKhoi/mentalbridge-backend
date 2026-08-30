package com.mentalbridge.care.assessment;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_profile")
class UserProfileEntity {

	@Id
	private UUID accountId;

	protected UserProfileEntity() {
	}

	UUID accountId() {
		return accountId;
	}
}

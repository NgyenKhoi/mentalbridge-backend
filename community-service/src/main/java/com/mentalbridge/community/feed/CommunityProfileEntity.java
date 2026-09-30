package com.mentalbridge.community.feed;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "community_profile")
@BatchSize(size = 50)
class CommunityProfileEntity {

	enum Status { ACTIVE, DELETED }

	@Id
	private UUID id;

	@Column(name = "account_subject", nullable = false, unique = true)
	private UUID accountSubject;

	@Column(name = "display_name", nullable = false, length = 80)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(name = "avatar_preset", length = 24)
	private AvatarPreset avatarPreset;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private Status status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private long version;

	protected CommunityProfileEntity() {
	}

	CommunityProfileEntity(UUID id, UUID accountSubject, String displayName, AvatarPreset avatarPreset, Instant now) {
		this.id = id;
		this.accountSubject = accountSubject;
		this.displayName = displayName;
		this.avatarPreset = avatarPreset;
		this.status = Status.ACTIVE;
		this.createdAt = now;
		this.updatedAt = now;
	}

	void update(String displayName, AvatarPreset avatarPreset, Instant now) {
		this.displayName = displayName;
		this.avatarPreset = avatarPreset;
		this.updatedAt = now;
	}

	UUID id() {
		return id;
	}

	UUID accountSubject() {
		return accountSubject;
	}

	String displayName() {
		return displayName;
	}

	AvatarPreset avatarPreset() {
		return avatarPreset;
	}

	Status status() {
		return status;
	}

	Instant createdAt() {
		return createdAt;
	}

	Instant updatedAt() {
		return updatedAt;
	}

	long version() {
		return version;
	}
}

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

	UUID id() {
		return id;
	}

	UUID accountSubject() {
		return accountSubject;
	}

	String displayName() {
		return displayName;
	}

	Status status() {
		return status;
	}
}

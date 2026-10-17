package com.mentalbridge.community.feed;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "community_comment")
class CommunityCommentEntity {

	enum State { ACTIVE, OWNER_DELETED, MODERATION_HIDDEN, MODERATION_REMOVED }

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "post_id", nullable = false)
	private CommunityPostEntity post;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parent_comment_id")
	private CommunityCommentEntity parent;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_profile_id", nullable = false)
	private CommunityProfileEntity author;

	@Column(nullable = false, length = 2000)
	private String content;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 24)
	private State state;

	@Column(name = "idempotency_key", nullable = false, length = 128)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, length = 64)
	private String requestFingerprint;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private long version;

	protected CommunityCommentEntity() {
	}

	CommunityCommentEntity(UUID id, CommunityPostEntity post, CommunityCommentEntity parent,
			CommunityProfileEntity author, String content, String idempotencyKey, String requestFingerprint,
			Instant now) {
		this.id = id;
		this.post = post;
		this.parent = parent;
		this.author = author;
		this.content = content;
		this.state = State.ACTIVE;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.createdAt = now;
		this.updatedAt = now;
	}

	void edit(String content, Instant now) {
		this.content = content;
		this.updatedAt = now;
	}

	void delete(Instant now) {
		this.state = State.OWNER_DELETED;
		this.updatedAt = now;
	}

	UUID id() {
		return id;
	}

	CommunityPostEntity post() {
		return post;
	}

	CommunityCommentEntity parent() {
		return parent;
	}

	CommunityProfileEntity author() {
		return author;
	}

	String content() {
		return content;
	}

	State state() {
		return state;
	}

	String requestFingerprint() {
		return requestFingerprint;
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

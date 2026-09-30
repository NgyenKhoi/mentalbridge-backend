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
@Table(name = "community_media")
class CommunityMediaEntity {

	enum Type { IMAGE, VIDEO }

	enum State { PENDING, PROCESSING, READY, REJECTED, DELETED, EXPIRED }

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_profile_id", nullable = false)
	private CommunityProfileEntity owner;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "post_id")
	private CommunityPostEntity post;

	@Enumerated(EnumType.STRING)
	@Column(name = "media_type", nullable = false, length = 16)
	private Type mediaType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private State state;

	@Column(name = "delivery_url", length = 2048)
	private String deliveryUrl;

	@Column(name = "storage_provider", length = 24)
	private String storageProvider;

	@Column(name = "storage_key", length = 512)
	private String storageKey;

	@Column(name = "expected_mime_type", length = 120)
	private String expectedMimeType;

	@Column(name = "expected_size_bytes")
	private Long expectedSizeBytes;

	@Column(name = "upload_expires_at")
	private Instant uploadExpiresAt;

	@Column(name = "idempotency_key", length = 128)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", length = 64)
	private String requestFingerprint;

	@Column(name = "rejection_reason", length = 64)
	private String rejectionReason;

	private Integer width;
	private Integer height;

	@Column(name = "duration_seconds")
	private Integer durationSeconds;

	@Column(name = "alt_text", length = 300)
	private String altText;

	@Column(nullable = false)
	private short position;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private long version;

	protected CommunityMediaEntity() {
	}

	CommunityMediaEntity(UUID id, CommunityProfileEntity owner, Type mediaType, String storageKey,
			String expectedMimeType, long expectedSizeBytes, Instant uploadExpiresAt, String idempotencyKey,
			String requestFingerprint, Instant now) {
		this.id = id;
		this.owner = owner;
		this.mediaType = mediaType;
		this.state = State.PENDING;
		this.storageProvider = "CLOUDINARY";
		this.storageKey = storageKey;
		this.expectedMimeType = expectedMimeType;
		this.expectedSizeBytes = expectedSizeBytes;
		this.uploadExpiresAt = uploadExpiresAt;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.position = 0;
		this.createdAt = now;
		this.updatedAt = now;
	}

	UUID id() {
		return id;
	}

	Type mediaType() {
		return mediaType;
	}

	State state() {
		return state;
	}

	String storageKey() {
		return storageKey;
	}

	String expectedMimeType() {
		return expectedMimeType;
	}

	Long expectedSizeBytes() {
		return expectedSizeBytes;
	}

	Instant uploadExpiresAt() {
		return uploadExpiresAt;
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

	String deliveryUrl() {
		return deliveryUrl;
	}

	Integer width() {
		return width;
	}

	Integer height() {
		return height;
	}

	Integer durationSeconds() {
		return durationSeconds;
	}

	String altText() {
		return altText;
	}

	CommunityProfileEntity owner() {
		return owner;
	}

	CommunityPostEntity post() {
		return post;
	}

	void attachTo(CommunityPostEntity post, short position, Instant now) {
		this.post = post;
		this.position = position;
		this.updatedAt = now;
	}

	void detach(Instant now) {
		this.post = null;
		this.position = 0;
		this.updatedAt = now;
	}

	void markReady(String deliveryUrl, int width, int height, Integer durationSeconds, Instant now) {
		this.state = State.READY;
		this.deliveryUrl = deliveryUrl;
		this.width = width;
		this.height = height;
		this.durationSeconds = durationSeconds;
		this.rejectionReason = null;
		this.updatedAt = now;
	}

	void reject(String reason, Instant now) {
		this.state = State.REJECTED;
		this.deliveryUrl = null;
		this.width = null;
		this.height = null;
		this.durationSeconds = null;
		this.rejectionReason = reason;
		this.updatedAt = now;
	}

	void delete(Instant now) {
		this.state = State.DELETED;
		this.deliveryUrl = null;
		this.width = null;
		this.height = null;
		this.durationSeconds = null;
		this.rejectionReason = null;
		this.post = null;
		this.position = 0;
		this.updatedAt = now;
	}

	void expire(Instant now) {
		this.state = State.EXPIRED;
		this.deliveryUrl = null;
		this.width = null;
		this.height = null;
		this.durationSeconds = null;
		this.rejectionReason = null;
		this.updatedAt = now;
	}

	void clearStorageReference(Instant now) {
		this.storageProvider = null;
		this.storageKey = null;
		this.uploadExpiresAt = null;
		this.updatedAt = now;
	}
}

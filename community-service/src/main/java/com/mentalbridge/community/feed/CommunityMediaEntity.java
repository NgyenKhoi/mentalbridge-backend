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

	UUID id() {
		return id;
	}

	Type mediaType() {
		return mediaType;
	}

	State state() {
		return state;
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
}

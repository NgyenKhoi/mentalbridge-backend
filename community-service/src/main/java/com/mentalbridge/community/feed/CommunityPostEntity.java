package com.mentalbridge.community.feed;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "community_post")
class CommunityPostEntity {

	enum State { ACTIVE, OWNER_DELETED, MODERATION_HIDDEN, MODERATION_REMOVED }

	enum AuthorMode { PROFILE, ANONYMOUS }

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_profile_id", nullable = false)
	private CommunityProfileEntity author;

	@Column(nullable = false, length = 5000)
	private String content;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 24)
	private State state;

	@Column(name = "comment_count", nullable = false)
	private int commentCount;

	@Column(name = "reaction_count", nullable = false)
	private int reactionCount;

	@Column(name = "published_at", nullable = false)
	private Instant publishedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "idempotency_key", length = 128)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", length = 64)
	private String requestFingerprint;

	@Enumerated(EnumType.STRING)
	@Column(name = "author_mode", nullable = false, length = 16)
	private AuthorMode authorMode;

	@Version
	private Long version;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "community_post_topic", joinColumns = @JoinColumn(name = "post_id"))
	@Column(name = "topic_code", nullable = false, length = 32)
	@Enumerated(EnumType.STRING)
	@BatchSize(size = 50)
	private Set<CommunityTopic> topics = new LinkedHashSet<>();

	@OneToMany(mappedBy = "post", fetch = FetchType.LAZY)
	@OrderBy("position ASC, id ASC")
	@BatchSize(size = 50)
	private List<CommunityMediaEntity> media = new ArrayList<>();

	protected CommunityPostEntity() {
	}

	CommunityPostEntity(UUID id, CommunityProfileEntity author, String content, Set<CommunityTopic> topics,
			String idempotencyKey, String requestFingerprint, AuthorMode authorMode, Instant now) {
		this.id = id;
		this.author = author;
		this.content = content;
		this.state = State.ACTIVE;
		this.commentCount = 0;
		this.reactionCount = 0;
		this.publishedAt = now;
		this.updatedAt = now;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.authorMode = authorMode;
		this.topics.addAll(topics);
	}

	UUID id() {
		return id;
	}

	CommunityProfileEntity author() {
		return author;
	}

	String content() {
		return content;
	}

	int commentCount() {
		return commentCount;
	}

	int reactionCount() {
		return reactionCount;
	}

	Instant publishedAt() {
		return publishedAt;
	}

	Instant updatedAt() {
		return updatedAt;
	}

	Set<CommunityTopic> topics() {
		return Set.copyOf(topics);
	}

	List<CommunityMediaEntity> media() {
		return List.copyOf(media);
	}

	State state() {
		return state;
	}

	long version() {
		return version;
	}

	String requestFingerprint() {
		return requestFingerprint;
	}

	AuthorMode authorMode() {
		return authorMode;
	}

	void update(String content, Set<CommunityTopic> topics, AuthorMode authorMode, Instant now) {
		this.content = content;
		this.topics.clear();
		this.topics.addAll(topics);
		this.authorMode = authorMode;
		this.updatedAt = now;
	}

	void delete(Instant now) {
		this.state = State.OWNER_DELETED;
		this.updatedAt = now;
	}

	void addMedia(CommunityMediaEntity item) {
		if (!media.contains(item)) {
			media.add(item);
		}
	}

	void removeMedia(CommunityMediaEntity item) {
		media.remove(item);
	}

	void addComment(Instant now) {
		commentCount++;
		updatedAt = now;
	}

	void removeComment(Instant now) {
		if (commentCount == 0) {
			throw new IllegalStateException("Comment count cannot be negative");
		}
		commentCount--;
		updatedAt = now;
	}
}

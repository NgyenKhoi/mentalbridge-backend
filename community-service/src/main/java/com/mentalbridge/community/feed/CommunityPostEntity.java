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

	@Version
	private long version;

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
}

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

@Entity
@Table(name = "community_comment_revision")
class CommunityCommentRevisionEntity {

	enum ChangeType { CREATED, EDITED, OWNER_DELETED, MODERATED }

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "comment_id", nullable = false)
	private CommunityCommentEntity comment;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "changed_by_profile_id", nullable = false)
	private CommunityProfileEntity changedBy;

	@Enumerated(EnumType.STRING)
	@Column(name = "change_type", nullable = false, length = 16)
	private ChangeType changeType;

	@Column(name = "content_snapshot", nullable = false, length = 2000)
	private String contentSnapshot;

	@Enumerated(EnumType.STRING)
	@Column(name = "state_snapshot", nullable = false, length = 24)
	private CommunityCommentEntity.State stateSnapshot;

	@Column(name = "comment_version", nullable = false)
	private long commentVersion;

	@Column(name = "changed_at", nullable = false)
	private Instant changedAt;

	protected CommunityCommentRevisionEntity() {
	}

	CommunityCommentRevisionEntity(UUID id, CommunityCommentEntity comment, CommunityProfileEntity changedBy,
			ChangeType changeType, String contentSnapshot, CommunityCommentEntity.State stateSnapshot,
			long commentVersion, Instant changedAt) {
		this.id = id;
		this.comment = comment;
		this.changedBy = changedBy;
		this.changeType = changeType;
		this.contentSnapshot = contentSnapshot;
		this.stateSnapshot = stateSnapshot;
		this.commentVersion = commentVersion;
		this.changedAt = changedAt;
	}
}

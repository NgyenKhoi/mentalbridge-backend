package com.mentalbridge.community.feed;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "community_post_bookmark")
class CommunityPostBookmarkEntity {

	@EmbeddedId
	private CommunityPostBookmarkId id;

	@MapsId("postId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "post_id", nullable = false)
	private CommunityPostEntity post;

	@MapsId("profileId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "profile_id", nullable = false)
	private CommunityProfileEntity profile;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected CommunityPostBookmarkEntity() {
	}

	CommunityPostBookmarkEntity(CommunityPostEntity post, CommunityProfileEntity profile, Instant now) {
		this.id = new CommunityPostBookmarkId(post.id(), profile.id());
		this.post = post;
		this.profile = profile;
		this.createdAt = now;
	}

	UUID postId() {
		return id.postId;
	}

	@Embeddable
	static class CommunityPostBookmarkId implements Serializable {

		private UUID postId;
		private UUID profileId;

		protected CommunityPostBookmarkId() {
		}

		CommunityPostBookmarkId(UUID postId, UUID profileId) {
			this.postId = postId;
			this.profileId = profileId;
		}

		@Override
		public boolean equals(Object candidate) {
			return this == candidate || candidate instanceof CommunityPostBookmarkId other
					&& Objects.equals(postId, other.postId) && Objects.equals(profileId, other.profileId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(postId, profileId);
		}
	}
}

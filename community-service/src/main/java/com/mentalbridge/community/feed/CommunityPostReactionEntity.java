package com.mentalbridge.community.feed;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "community_post_reaction")
class CommunityPostReactionEntity {

	enum Reaction { SUPPORT, RELATE, THANK_YOU }

	@EmbeddedId
	private CommunityPostReactionId id;

	@MapsId("postId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "post_id", nullable = false)
	private CommunityPostEntity post;

	@MapsId("profileId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "profile_id", nullable = false)
	private CommunityProfileEntity profile;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private Reaction reaction;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected CommunityPostReactionEntity() {
	}

	CommunityPostReactionEntity(CommunityPostEntity post, CommunityProfileEntity profile, Reaction reaction,
			Instant now) {
		this.id = new CommunityPostReactionId(post.id(), profile.id());
		this.post = post;
		this.profile = profile;
		this.reaction = reaction;
		this.createdAt = now;
		this.updatedAt = now;
	}

	Reaction reaction() {
		return reaction;
	}

	UUID postId() {
		return id.postId;
	}

	void replace(Reaction replacement, Instant now) {
		this.reaction = replacement;
		this.updatedAt = now;
	}

	@Embeddable
	static class CommunityPostReactionId implements Serializable {

		private UUID postId;
		private UUID profileId;

		protected CommunityPostReactionId() {
		}

		CommunityPostReactionId(UUID postId, UUID profileId) {
			this.postId = postId;
			this.profileId = profileId;
		}

		@Override
		public boolean equals(Object candidate) {
			return this == candidate || candidate instanceof CommunityPostReactionId other
					&& Objects.equals(postId, other.postId) && Objects.equals(profileId, other.profileId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(postId, profileId);
		}
	}
}

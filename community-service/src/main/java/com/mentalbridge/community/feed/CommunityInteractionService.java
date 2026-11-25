package com.mentalbridge.community.feed;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.community.feed.CommunityPostReactionEntity.CommunityPostReactionId;
import com.mentalbridge.community.feed.CommunityPostReactionEntity.Reaction;
import com.mentalbridge.community.feed.CommunityPostBookmarkEntity.CommunityPostBookmarkId;
import com.mentalbridge.community.shared.CommunityApiException;

@Service
class CommunityInteractionService {

	private static final String DEFAULT_DISPLAY_NAME = "Thành viên MentalBridge";

	private final CommunityProfileRepository profiles;
	private final CommunityPostRepository posts;
	private final CommunityPostReactionRepository reactions;
	private final CommunityPostBookmarkRepository bookmarks;
	private final CommunityInteractionEventPolicy interactionEvents;
	private final JdbcClient jdbc;

	CommunityInteractionService(CommunityProfileRepository profiles, CommunityPostRepository posts,
			CommunityPostReactionRepository reactions, CommunityPostBookmarkRepository bookmarks,
			CommunityInteractionEventPolicy interactionEvents, JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.profiles = profiles;
		this.posts = posts;
		this.reactions = reactions;
		this.bookmarks = bookmarks;
		this.interactionEvents = interactionEvents;
	}

	@Transactional
	CommunityReaction putReaction(UUID subject, UUID postId, Reaction reaction) {
		var profile = activeProfile(subject);
		var post = visiblePostForUpdate(postId, profile.id());
		var id = new CommunityPostReactionId(postId, profile.id());
		var existing = reactions.findById(id);
		var now = Instant.now();
		var created = existing.isEmpty();
		if (existing.isEmpty()) {
			reactions.save(new CommunityPostReactionEntity(post, profile, reaction, now));
			post.addReaction(now);
		}
		else if (existing.orElseThrow().reaction() != reaction) {
			existing.orElseThrow().replace(reaction, now);
		}
		reactions.flush();
		posts.flush();
		if (created) {
			interactionEvents.reactionCreated(post, profile, reaction, now);
		}
		return new CommunityReaction(postId, reaction);
	}

	@Transactional
	void deleteReaction(UUID subject, UUID postId) {
		var profileId = profiles.findIdByAccountSubject(subject).orElse(null);
		var post = visiblePostForUpdate(postId, profileId);
		if (profileId == null) {
			return;
		}
		var existing = reactions.findById(new CommunityPostReactionId(postId, profileId));
		if (existing.isPresent()) {
			reactions.delete(existing.orElseThrow());
			post.removeReaction(Instant.now());
			reactions.flush();
			posts.flush();
		}
	}

	@Transactional
	void putBookmark(UUID subject, UUID postId) {
		var profile = activeProfile(subject);
		var post = visiblePostForUpdate(postId, profile.id());
		var id = new CommunityPostBookmarkId(postId, profile.id());
		if (!bookmarks.existsById(id)) {
			bookmarks.saveAndFlush(new CommunityPostBookmarkEntity(post, profile, Instant.now()));
		}
	}

	@Transactional
	void deleteBookmark(UUID subject, UUID postId) {
		var profileId = profiles.findIdByAccountSubject(subject).orElse(null);
		visiblePostForUpdate(postId, profileId);
		if (profileId != null) {
			bookmarks.deleteById(new CommunityPostBookmarkId(postId, profileId));
			bookmarks.flush();
		}
	}

	/**
	 * Who reacted to a post the viewer can see: Community display identity only (never the account subject),
	 * newest first, at most 100, without profiles either side has blocked.
	 */
	@Transactional
	ReactionList listReactions(UUID subject, UUID postId) {
		var viewer = activeProfile(subject).id();
		posts.findVisibleById(postId, viewer).orElseThrow(CommunityApiException::postNotFound);
		var items = jdbc.sql("""
				SELECT r.profile_id, p.display_name, p.avatar_preset, r.reaction, r.updated_at
				FROM community_post_reaction r
				JOIN community_profile p ON p.id = r.profile_id
				WHERE r.post_id = :postId AND p.status = 'ACTIVE'
				  AND NOT EXISTS (
				      SELECT 1 FROM community_block b
				      WHERE (b.blocker_profile_id = :viewer AND b.blocked_profile_id = r.profile_id)
				         OR (b.blocker_profile_id = r.profile_id AND b.blocked_profile_id = :viewer))
				ORDER BY r.updated_at DESC
				LIMIT 100
				""").param("postId", postId).param("viewer", viewer)
				.query((rs, row) -> new Reactor(rs.getObject("profile_id", UUID.class), rs.getString("display_name"),
						rs.getString("avatar_preset") == null ? null : AvatarPreset.valueOf(rs.getString("avatar_preset")),
						Reaction.valueOf(rs.getString("reaction")), rs.getTimestamp("updated_at").toInstant(),
						rs.getObject("profile_id", UUID.class).equals(viewer)))
				.list();
		return new ReactionList(items, items.size());
	}

	private CommunityProfileEntity activeProfile(UUID subject) {
		var now = Instant.now();
		profiles.createIfAbsent(UUID.randomUUID(), subject, DEFAULT_DISPLAY_NAME, now);
		return profiles.findByAccountSubjectForUpdate(subject)
				.filter(profile -> profile.status() == CommunityProfileEntity.Status.ACTIVE)
				.orElseThrow(CommunityApiException::communityAccessUnavailable);
	}

	private CommunityPostEntity visiblePostForUpdate(UUID postId, UUID profileId) {
		if (profileId == null) {
			return posts.findActiveByIdForUpdate(postId).orElseThrow(CommunityApiException::postNotFound);
		}
		return posts.findVisibleByIdForUpdate(postId, profileId).orElseThrow(CommunityApiException::postNotFound);
	}

	record CommunityReaction(UUID postId, Reaction reaction) {
	}

	record Reactor(UUID communityProfileId, String displayName, AvatarPreset avatarPreset, Reaction reaction,
			Instant reactedAt, boolean self) {
	}

	record ReactionList(List<Reactor> items, int count) {
	}
}

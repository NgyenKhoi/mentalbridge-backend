package com.mentalbridge.community.feed;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CommunityResponses {

	private CommunityResponses() {
	}

	public record Feed(List<PostSummary> items, String nextCursor, boolean hasMore) {
	}

	public record PostSummary(UUID postId, Author author, String contentPreview, List<CommunityTopic> topics,
			List<Media> media, MediaAvailability mediaAvailability, Counts counts, Instant publishedAt,
			Instant updatedAt) {
	}

	public record PostDetail(UUID postId, Author author, String content, List<CommunityTopic> topics,
			List<Media> media, MediaAvailability mediaAvailability, Counts counts, Instant publishedAt,
			Instant updatedAt) {
	}

	public record Author(UUID communityProfileId, String displayName, AvatarPreset avatarPreset, AuthorState state) {
	}

	public enum AuthorState { ACTIVE, DELETED, ANONYMOUS }

	public record Media(UUID mediaId, MediaType type, String url, Integer width, Integer height,
			Integer durationSeconds, String altText) {
	}

	public enum MediaType { IMAGE, VIDEO }

	public enum MediaAvailability { NONE, READY, PARTIAL, UNAVAILABLE }

	public record Counts(int comments, int reactions) {
	}

	public record Topic(CommunityTopic code, String label, String description) {
	}
}

package com.mentalbridge.community.feed;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mentalbridge.community.feed.CommunityResponses.Author;

final class CommunityCommentResponses {

	private CommunityCommentResponses() {
	}

	record Comment(UUID commentId, UUID postId, UUID parentCommentId, Author author, String content,
			CommunityCommentEntity.State state, long version, Instant createdAt, Instant updatedAt) {
	}

	record Page(List<Comment> items, String nextCursor, boolean hasMore) {
	}

	record VersionedComment(Comment body, long version) {
	}
}

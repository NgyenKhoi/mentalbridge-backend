package com.mentalbridge.community.feed;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

final class CommunityCommentRequests {

	private CommunityCommentRequests() {
	}

	record CreateCommentRequest(@NotNull String content, UUID parentCommentId) {
	}

	record UpdateCommentRequest(@NotNull String content) {
	}
}

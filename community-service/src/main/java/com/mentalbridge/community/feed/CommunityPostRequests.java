package com.mentalbridge.community.feed;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

final class CommunityPostRequests {

	private CommunityPostRequests() {
	}

	record WritePostRequest(@NotNull String content, @NotNull List<CommunityTopic> topics,
			@NotNull List<UUID> mediaIds, CommunityPostEntity.AuthorMode authorMode) {
	}
}

package com.mentalbridge.community.feed;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

final class CommunityMediaResponses {

	private CommunityMediaResponses() {
	}

	record UploadIntent(UUID mediaId, CommunityMediaEntity.State state, URI uploadUrl, Instant expiresAt,
			Map<String, String> uploadFields, long version) {
	}

	record MediaRecord(UUID mediaId, CommunityMediaEntity.Type mediaType, CommunityMediaEntity.State state,
			long version, Instant createdAt, Instant updatedAt) {
	}
}

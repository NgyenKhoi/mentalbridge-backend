package com.mentalbridge.community.feed;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

final class CommunityMediaRequests {

	private CommunityMediaRequests() {
	}

	record CreateUploadIntentRequest(@NotBlank String fileName, @NotNull CommunityMediaEntity.Type mediaType,
			@NotBlank String mimeType, @Positive long sizeBytes) {
	}
}

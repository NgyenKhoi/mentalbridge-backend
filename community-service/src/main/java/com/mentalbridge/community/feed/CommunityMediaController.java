package com.mentalbridge.community.feed;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.community.feed.CommunityMediaRequests.CreateUploadIntentRequest;
import com.mentalbridge.community.feed.CommunityMediaResponses.MediaRecord;
import com.mentalbridge.community.feed.CommunityMediaResponses.UploadIntent;
import com.mentalbridge.community.shared.CommunityApiException;

@RestController
@RequestMapping("/api/v1/community/media")
public class CommunityMediaController {

	private final CommunityMediaService media;

	public CommunityMediaController(CommunityMediaService media) {
		this.media = media;
	}

	@PostMapping("/upload-intents")
	ResponseEntity<UploadIntent> createIntent(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody CreateUploadIntentRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(media.createIntent(subject(jwt), idempotencyKey, request));
	}

	@PostMapping("/{mediaId}/finalize")
	MediaRecord finalizeUpload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mediaId) {
		return media.finalizeUpload(subject(jwt), mediaId);
	}

	@DeleteMapping("/{mediaId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mediaId,
			@RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) {
		media.delete(subject(jwt), mediaId, parseVersion(ifMatch));
		return ResponseEntity.noContent().build();
	}

	private UUID subject(Jwt jwt) {
		try {
			return UUID.fromString(jwt.getSubject());
		}
		catch (IllegalArgumentException exception) {
			throw CommunityApiException.invalidSubject();
		}
	}

	private long parseVersion(String value) {
		if (value == null || !value.matches("\\\"(0|[1-9]\\d*)\\\"")) {
			throw CommunityApiException.invalidVersionHeader();
		}
		try {
			return Long.parseLong(value.substring(1, value.length() - 1));
		}
		catch (NumberFormatException exception) {
			throw CommunityApiException.invalidVersionHeader();
		}
	}
}

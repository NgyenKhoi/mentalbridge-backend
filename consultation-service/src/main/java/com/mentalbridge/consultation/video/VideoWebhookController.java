package com.mentalbridge.consultation.video;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class VideoWebhookController {

	private final VideoProvider provider;
	private final VideoEvidenceService evidence;

	VideoWebhookController(VideoProvider provider, VideoEvidenceService evidence) {
		this.provider = provider;
		this.evidence = evidence;
	}

	@PostMapping(value = "/internal/v1/video/livekit/webhook", consumes = "application/webhook+json")
	ResponseEntity<Void> receive(@RequestBody String body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		evidence.record(provider.verifyWebhook(body, authorization));
		return ResponseEntity.noContent().build();
	}
}

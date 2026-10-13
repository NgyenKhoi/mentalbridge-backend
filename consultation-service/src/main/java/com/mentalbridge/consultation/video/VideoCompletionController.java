package com.mentalbridge.consultation.video;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.ApiException;
import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
class VideoCompletionController {

	private final VideoCompletionService service;

	VideoCompletionController(VideoCompletionService service) { this.service = service; }

	@PostMapping("/api/v1/specialist/appointments/{appointmentId}/video-completion")
	ResponseEntity<VideoCompletionResponse> confirm(@PathVariable UUID appointmentId, @AuthenticationPrincipal Jwt jwt,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@RequestBody Map<String, Object> body) {
		if (body.size() != 1 || !"CONSULTATION_FINISHED".equals(body.get("confirmation"))
				|| key == null || !key.matches("[!-~]{16,128}")) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VIDEO_CONFIRMATION_INVALID", "Video confirmation and a valid idempotency key are required");
		}
		long version;
		try {
			if (ifMatch == null || !ifMatch.matches("\"[0-9]{1,19}\"")) throw new IllegalArgumentException();
			version = Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "APPOINTMENT_VERSION_REQUIRED", "If-Match must contain the quoted current appointment version");
		}
		var result = service.confirm(appointmentId, RequestIdentity.subject(jwt), RequestIdentity.role(jwt), version, key);
		var response = result.response();
		return ResponseEntity.status(result.replay() || "VERIFIED".equals(response.verificationState()) ? 200 : 202)
				.cacheControl(CacheControl.noStore()).eTag("\"" + response.version() + "\"").body(response);
	}
}

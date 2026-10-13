package com.mentalbridge.consultation.video;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;
import com.mentalbridge.consultation.shared.ApiException;
import org.springframework.http.HttpStatus;

@RestController
class VideoSessionController {

	private final VideoSessionService service;

	VideoSessionController(VideoSessionService service) { this.service = service; }

	@GetMapping("/api/v1/appointments/{appointmentId}/video-session")
	ResponseEntity<VideoSessionResponse> session(@PathVariable UUID appointmentId, @AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(service.session(appointmentId, RequestIdentity.subject(jwt), RequestIdentity.role(jwt)));
	}

	@PostMapping("/api/v1/appointments/{appointmentId}/video-access")
	ResponseEntity<VideoAccessResponse> access(@PathVariable UUID appointmentId, @AuthenticationPrincipal Jwt jwt,
			@RequestBody(required = false) byte[] body) {
		if (body != null && body.length > 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VIDEO_ACCESS_INVALID", "Video access does not accept a request body");
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(service.access(appointmentId, RequestIdentity.subject(jwt), RequestIdentity.role(jwt)));
	}
}

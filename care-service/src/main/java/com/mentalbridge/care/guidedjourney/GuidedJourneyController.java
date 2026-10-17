package com.mentalbridge.care.guidedjourney;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.guidedjourney.GuidedJourneyService.JourneyView;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportguide.SupportGuideService.SupportGuideView;

@RestController
public class GuidedJourneyController {

	private final GuidedJourneyService journeys;

	public GuidedJourneyController(GuidedJourneyService journeys) {
		this.journeys = journeys;
	}

	@GetMapping("/api/v1/guided-journey/current")
	ResponseEntity<JourneyView> current(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(journeys.current(subject(jwt), jwt.getTokenValue(), correlation(correlationId)));
	}

	@PostMapping("/api/v1/screening-episodes/{episodeId}/support-guide")
	ResponseEntity<SupportGuideView> ensureGuide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID episodeId,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(journeys.ensureGuide(subject(jwt), jwt.getTokenValue(), correlation(correlationId), episodeId));
	}

	private UUID correlation(UUID correlationId) {
		return correlationId == null ? UUID.randomUUID() : correlationId;
	}

	private UUID subject(Jwt jwt) {
		try {
			if (jwt.getSubject() == null) throw new IllegalArgumentException();
			return UUID.fromString(jwt.getSubject());
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
					"Authenticated account identifier is invalid");
		}
	}
}

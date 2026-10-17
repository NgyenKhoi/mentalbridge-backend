package com.mentalbridge.care.consultationbrief;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;

@RestController
@RequestMapping("/api/v1/specialist/client-continuity")
public class SpecialistClientContinuityController {

	private final SpecialistClientContinuityService continuity;

	public SpecialistClientContinuityController(SpecialistClientContinuityService continuity) {
		this.continuity = continuity;
	}

	@GetMapping
	SpecialistClientContinuityService.ListView list(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
		return continuity.list(subject(jwt), jwt.getTokenValue(),
				correlationId == null ? UUID.randomUUID() : correlationId);
	}

	private UUID subject(Jwt jwt) {
		try { return UUID.fromString(jwt.getSubject()); }
		catch (IllegalArgumentException exception) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
					"Authenticated account identifier is invalid");
		}
	}
}

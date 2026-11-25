package com.mentalbridge.consultation.appointment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mentalbridge.consultation.shared.RequestIdentity;

import jakarta.validation.Valid;

@RestController
public class AppointmentChatEvidenceController {

	private final AppointmentChatEvidenceService evidence;
	private final byte[] serviceToken;

	public AppointmentChatEvidenceController(AppointmentChatEvidenceService evidence,
			@Value("${mentalbridge.consultation.evidence-service-token:}") String serviceToken) {
		this.evidence = evidence;
		this.serviceToken = serviceToken.getBytes(StandardCharsets.UTF_8);
	}

	@PostMapping("/internal/v1/appointments/{appointmentId}/chat-evidence")
	ChatEvidenceResponse record(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appointmentId,
			@RequestHeader(value = "X-MentalBridge-Service-Token", required = false) String candidateServiceToken,
			@Valid @RequestBody ChatEvidenceRequest request) {
		if (candidateServiceToken == null || serviceToken.length == 0 || !MessageDigest.isEqual(serviceToken,
				candidateServiceToken.getBytes(StandardCharsets.UTF_8))) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return evidence.record(RequestIdentity.subject(jwt), RequestIdentity.role(jwt), appointmentId, request);
	}
}

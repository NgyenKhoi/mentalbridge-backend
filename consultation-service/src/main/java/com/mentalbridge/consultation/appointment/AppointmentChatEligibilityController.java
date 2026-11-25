package com.mentalbridge.consultation.appointment;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
public class AppointmentChatEligibilityController {

	private final AppointmentChatEligibilityService eligibility;

	public AppointmentChatEligibilityController(AppointmentChatEligibilityService eligibility) {
		this.eligibility = eligibility;
	}

	@GetMapping("/internal/v1/appointments/{conversationId}/chat-eligibility")
	AppointmentChatEligibility eligibility(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversationId,
			@RequestParam AppointmentChatEligibility.Operation operation) {
		return eligibility.decide(RequestIdentity.subject(jwt), RequestIdentity.role(jwt), conversationId, operation);
	}
}

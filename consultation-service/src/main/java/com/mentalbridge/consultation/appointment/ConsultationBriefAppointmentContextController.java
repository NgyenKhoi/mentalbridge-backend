package com.mentalbridge.consultation.appointment;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
public class ConsultationBriefAppointmentContextController {

	private final ConsultationBriefAppointmentContextService contexts;

	public ConsultationBriefAppointmentContextController(ConsultationBriefAppointmentContextService contexts) {
		this.contexts = contexts;
	}

	@GetMapping("/internal/v1/appointments/{appointmentId}/consultation-brief-context")
	ConsultationBriefAppointmentContext read(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
			@PathVariable UUID appointmentId) {
		var authorities = authentication.getAuthorities();
		var user = authorities.stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_USER"));
		var specialist = authorities.stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_SPECIALIST"));
		return contexts.read(appointmentId, RequestIdentity.subject(jwt), user, specialist);
	}
}

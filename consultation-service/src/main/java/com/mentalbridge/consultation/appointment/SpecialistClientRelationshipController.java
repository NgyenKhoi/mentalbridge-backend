package com.mentalbridge.consultation.appointment;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
public class SpecialistClientRelationshipController {

	private final SpecialistClientRelationshipService relationships;

	public SpecialistClientRelationshipController(SpecialistClientRelationshipService relationships) {
		this.relationships = relationships;
	}

	@GetMapping("/internal/v1/specialist/client-relationships")
	SpecialistClientRelationshipProjection list(@AuthenticationPrincipal Jwt jwt) {
		return relationships.list(RequestIdentity.subject(jwt));
	}
}

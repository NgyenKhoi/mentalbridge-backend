package com.mentalbridge.consultation.analytics;

import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
public class SpecialistAnalyticsController {

	private final SpecialistAnalyticsService analytics;

	public SpecialistAnalyticsController(SpecialistAnalyticsService analytics) {
		this.analytics = analytics;
	}

	@GetMapping("/api/v1/specialist/analytics")
	SpecialistAnalyticsResponse analytics(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		return analytics.analytics(RequestIdentity.subject(jwt), from, to);
	}
}

package com.mentalbridge.consultation.discovery;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.appointment.AppointmentModality;
import com.mentalbridge.consultation.shared.RequestIdentity;
import com.mentalbridge.consultation.specialist.SupportArea;

@Validated
@RestController
public class DiscoveryController {

	private final DiscoveryService discovery;

	public DiscoveryController(DiscoveryService discovery) {
		this.discovery = discovery;
	}

	@GetMapping("/api/v1/specialists")
	DiscoveryResponse.Page discover(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) UUID supportEvaluationId,
			@RequestParam(required = false) SupportArea supportArea,
			@RequestParam(required = false) String language,
			@RequestParam(required = false) @Size(max = 64) String timezone,
			@RequestParam(required = false) AppointmentModality modality,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
			@RequestParam(required = false) @Size(max = 2048) String cursor) {
		return discovery.discover(RequestIdentity.subject(jwt), jwt.getTokenValue(),
				new DiscoveryService.Criteria(supportEvaluationId, supportArea, language, timezone, modality, from, to),
				limit, cursor);
	}

	@GetMapping("/api/v1/specialists/{specialistAccountId}")
	DiscoveryResponse.Item detail(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID specialistAccountId,
			@RequestParam(required = false) UUID supportEvaluationId,
			@RequestParam(required = false) String language,
			@RequestParam(required = false) @Size(max = 64) String timezone,
			@RequestParam(required = false) AppointmentModality modality,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		return discovery.detail(RequestIdentity.subject(jwt), jwt.getTokenValue(), specialistAccountId,
				new DiscoveryService.Criteria(supportEvaluationId, null, language, timezone, modality, from, to));
	}
}

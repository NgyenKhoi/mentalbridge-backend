package com.mentalbridge.care.consultationbrief;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.consultationbrief.ConsultationBriefAiDraftService.JobView;
import com.mentalbridge.care.shared.ApiException;

@RestController
@RequestMapping("/api/v1/consultation-briefs/{appointmentId}/ai-draft-jobs")
public class ConsultationBriefAiDraftController {

	private final ConsultationBriefAiDraftService jobs;

	public ConsultationBriefAiDraftController(ConsultationBriefAiDraftService jobs) {
		this.jobs = jobs;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	JobView create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appointmentId,
			@RequestHeader("If-Match") String ifMatch, @RequestHeader("Idempotency-Key") String idempotencyKey,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
		return jobs.create(subject(jwt), jwt.getTokenValue(), correlation(correlationId), appointmentId,
				version(ifMatch), idempotencyKey);
	}

	@GetMapping("/{jobId}")
	JobView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appointmentId, @PathVariable UUID jobId) {
		return jobs.get(subject(jwt), appointmentId, jobId);
	}

	private UUID subject(Jwt jwt) {
		try { return UUID.fromString(jwt.getSubject()); }
		catch (IllegalArgumentException exception) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authenticated account identifier is invalid");
		}
	}

	private long version(String value) {
		if (value == null || !value.matches("\\\"[0-9]+\\\"")) {
			throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "CONSULTATION_BRIEF_VERSION_REQUIRED",
					"If-Match must contain the quoted current consultation brief version");
		}
		try { return Long.parseLong(value.substring(1, value.length() - 1)); }
		catch (NumberFormatException exception) {
			throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "CONSULTATION_BRIEF_VERSION_REQUIRED",
					"If-Match must contain the quoted current consultation brief version");
		}
	}

	private UUID correlation(UUID supplied) {
		return supplied == null ? UUID.randomUUID() : supplied;
	}
}

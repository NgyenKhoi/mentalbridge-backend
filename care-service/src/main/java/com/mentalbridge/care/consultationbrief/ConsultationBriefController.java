package com.mentalbridge.care.consultationbrief;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.consultationbrief.ConsultationBriefService.BriefView;
import com.mentalbridge.care.consultationbrief.ConsultationBriefService.SaveDraftCommand;
import com.mentalbridge.care.consultationbrief.ConsultationBriefService.ScreeningContextList;
import com.mentalbridge.care.consultationbrief.ConsultationBriefService.SpecialistBriefView;
import com.mentalbridge.care.shared.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1")
@Validated
public class ConsultationBriefController {

	private final ConsultationBriefService briefs;

	public ConsultationBriefController(ConsultationBriefService briefs) {
		this.briefs = briefs;
	}

	@PutMapping("/consultation-briefs/{appointmentId}/draft")
	ResponseEntity<BriefView> saveDraft(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appointmentId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			@Valid @RequestBody SaveDraftRequest request) {
		var result = briefs.saveDraft(subject(jwt), jwt.getTokenValue(), correlation(correlationId), appointmentId,
				optionalVersion(ifMatch), new SaveDraftCommand(request.currentSituation(),
						request.supportEvaluationId(), request.userGoals()));
		return response(result);
	}

	@GetMapping("/consultation-briefs/screening-contexts")
	ScreeningContextList screeningContexts(@AuthenticationPrincipal Jwt jwt) {
		return briefs.screeningContexts(subject(jwt));
	}

	@GetMapping("/consultation-briefs/{appointmentId}")
	ResponseEntity<BriefView> own(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appointmentId,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
		return response(briefs.own(subject(jwt), jwt.getTokenValue(), correlation(correlationId), appointmentId));
	}

	@PostMapping("/consultation-briefs/{appointmentId}/approve")
	ResponseEntity<BriefView> approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appointmentId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
		return response(briefs.approve(subject(jwt), jwt.getTokenValue(), correlation(correlationId), appointmentId,
				requiredVersion(ifMatch)));
	}

	@PostMapping("/consultation-briefs/{appointmentId}/revoke")
	ResponseEntity<BriefView> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appointmentId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
		return response(briefs.revoke(subject(jwt), jwt.getTokenValue(), correlation(correlationId), appointmentId,
				requiredVersion(ifMatch)));
	}

	@DeleteMapping("/consultation-briefs/{appointmentId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appointmentId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
		briefs.delete(subject(jwt), jwt.getTokenValue(), correlation(correlationId), appointmentId,
				requiredVersion(ifMatch));
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/specialist/consultation-briefs/{appointmentId}")
	SpecialistBriefView specialist(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appointmentId,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
		return briefs.specialist(subject(jwt), jwt.getTokenValue(), correlation(correlationId), appointmentId);
	}

	private ResponseEntity<BriefView> response(BriefView value) {
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(value.version()) + '"').body(value);
	}

	private UUID subject(Jwt jwt) {
		try { return UUID.fromString(jwt.getSubject()); }
		catch (IllegalArgumentException exception) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authenticated account identifier is invalid");
		}
	}

	private UUID correlation(UUID supplied) {
		return supplied == null ? UUID.randomUUID() : supplied;
	}

	private Long optionalVersion(String value) {
		return value == null ? null : requiredVersion(value);
	}

	private long requiredVersion(String value) {
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

	public record SaveDraftRequest(@NotBlank @Size(max = 1000) String currentSituation,
			@NotNull UUID supportEvaluationId,
			@NotNull @Size(min = 1, max = 5) List<@NotBlank @Size(max = 200) String> userGoals) { }
}

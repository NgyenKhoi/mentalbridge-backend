package com.mentalbridge.care.supportplan;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;

@RestController
@Validated
public class ResourceReconciliationController {
	private final ResourceReconciliationService resources;

	public ResourceReconciliationController(ResourceReconciliationService resources) { this.resources = resources; }

	@PostMapping("/api/v2/support-plan-occurrences/{occurrenceId}/resource/reconcile")
	ResponseEntity<SupportPlanV3Service.ActionOccurrenceView> reconcile(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID occurrenceId, @RequestHeader("If-Match") String ifMatch,
			@RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId) {
		UUID userId;
		try { userId = UUID.fromString(jwt.getSubject()); }
		catch (IllegalArgumentException exception) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authenticated account identifier is invalid");
		}
		var value = resources.reconcile(userId, occurrenceId, version(ifMatch), jwt.getTokenValue(), correlationId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.ETAG, '"' + Long.toString(value.version()) + '"').body(value);
	}

	private long version(String ifMatch) {
		try {
			if (!ifMatch.matches("^\"[0-9]+\"$")) throw new NumberFormatException();
			return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
		}
		catch (NumberFormatException exception) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "If-Match must contain a valid occurrence version");
		}
	}
}

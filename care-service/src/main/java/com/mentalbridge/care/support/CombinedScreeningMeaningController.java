package com.mentalbridge.care.support;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;

@RestController
public class CombinedScreeningMeaningController {

	private final CombinedScreeningMeaningService meanings;

	public CombinedScreeningMeaningController(CombinedScreeningMeaningService meanings) {
		this.meanings = meanings;
	}

	@GetMapping("/api/v1/support-evaluations/{supportEvaluationId}/combined-meaning")
	ResponseEntity<CombinedScreeningMeaningService.CombinedMeaningView> get(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID supportEvaluationId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(meanings.get(subject(jwt), supportEvaluationId));
	}

	private UUID subject(Jwt jwt) {
		try {
			if (jwt.getSubject() == null) throw new IllegalArgumentException();
			return UUID.fromString(jwt.getSubject());
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
					"Authenticated account identifier is invalid");
		}
	}
}

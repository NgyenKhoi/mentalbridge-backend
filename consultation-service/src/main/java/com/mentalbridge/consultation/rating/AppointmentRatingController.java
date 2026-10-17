package com.mentalbridge.consultation.rating;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.ApiException;
import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
public class AppointmentRatingController {

	private final AppointmentRatingService ratings;

	public AppointmentRatingController(AppointmentRatingService ratings) {
		this.ratings = ratings;
	}

	@GetMapping("/api/v1/appointments/{appointmentId}/rating")
	ResponseEntity<AppointmentRatingResponse> current(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID appointmentId) {
		var result = ratings.current(RequestIdentity.subject(jwt), appointmentId);
		return ResponseEntity.ok().eTag(Long.toString(result.version())).body(result);
	}

	@PutMapping("/api/v1/appointments/{appointmentId}/rating")
	ResponseEntity<AppointmentRatingResponse> save(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID appointmentId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@Valid @RequestBody SaveRating body) {
		var result = ratings.save(RequestIdentity.subject(jwt), appointmentId, body.rating(), version(ifMatch));
		return ResponseEntity.ok().eTag(Long.toString(result.version())).body(result);
	}

	private Long version(String ifMatch) {
		if (ifMatch == null) return null;
		if (!ifMatch.matches("\\\"[0-9]+\\\"")) throw required();
		try { return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1)); }
		catch (NumberFormatException exception) { throw required(); }
	}

	private ApiException required() {
		return new ApiException(HttpStatus.PRECONDITION_REQUIRED, "RATING_VERSION_REQUIRED",
				"If-Match must contain the quoted current rating version");
	}

	public record SaveRating(@NotNull @Min(1) @Max(5) Integer rating) { }
}

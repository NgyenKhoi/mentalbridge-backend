package com.mentalbridge.care.screeningepisode;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/screening-episodes/history")
@Validated
public class ScreeningEpisodeHistoryController {

	private final ScreeningEpisodeHistoryService history;

	public ScreeningEpisodeHistoryController(ScreeningEpisodeHistoryService history) {
		this.history = history;
	}

	@GetMapping
	ResponseEntity<ScreeningEpisodeHistoryService.HistoryPage> history(
			@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit,
			@RequestParam(required = false) @Size(min = 1, max = 256) String cursor) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(history.history(subject(jwt), limit, cursor));
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

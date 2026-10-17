package com.mentalbridge.care.supportguide;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportguide.SupportGuideService.GenerateCommand;
import com.mentalbridge.care.supportguide.SupportGuideService.HistoryView;
import com.mentalbridge.care.supportguide.SupportGuideService.SupportGuideView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/support-guides")
@Validated
public class SupportGuideController {

	private final SupportGuideService guides;

	public SupportGuideController(SupportGuideService guides) {
		this.guides = guides;
	}

	@PostMapping
	ResponseEntity<SupportGuideView> generate(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String key,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			@Valid @RequestBody GenerateRequest request) {
		var result = guides.generate(subject(jwt), jwt.getTokenValue(), key,
				correlationId == null ? UUID.randomUUID() : correlationId,
				new GenerateCommand(request.phq9AssessmentId(), request.gad7AssessmentId()));
		return ResponseEntity.created(URI.create("/api/v1/support-guides/" + result.supportGuideId())).body(result);
	}

	@GetMapping
	HistoryView history(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit,
			@RequestParam(required = false) @Size(max = 256) String cursor) {
		return guides.history(subject(jwt), limit, cursor);
	}

	@GetMapping("/{supportGuideId}")
	SupportGuideView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID supportGuideId) {
		return guides.get(subject(jwt), supportGuideId);
	}

	private UUID subject(Jwt jwt) {
		try {
			return UUID.fromString(jwt.getSubject());
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
					"Authenticated account identifier is invalid");
		}
	}

	public record GenerateRequest(@NotNull UUID phq9AssessmentId, @NotNull UUID gad7AssessmentId) { }
}

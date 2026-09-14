package com.mentalbridge.consultation.specialist;

import java.net.URI;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
@RequestMapping("/api/v1/specialist-profile")
public class SpecialistProfileController {

	private final SpecialistProfileService profiles;

	public SpecialistProfileController(SpecialistProfileService profiles) {
		this.profiles = profiles;
	}

	@GetMapping
	ResponseEntity<SpecialistProfileResponse> get(@AuthenticationPrincipal Jwt jwt) {
		var profile = profiles.getOwn(RequestIdentity.subject(jwt));
		return response(profile);
	}

	@PutMapping
	ResponseEntity<SpecialistProfileResponse> put(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@Valid @RequestBody SpecialistProfileRequest request) {
		var saved = profiles.saveDraft(RequestIdentity.subject(jwt), RequestIdentity.optionalVersion(ifMatch),
				request.command());
		var response = ResponseEntity.status(saved.created() ? 201 : 200)
				.eTag(Long.toString(saved.profile().version()));
		if (saved.created()) response.location(URI.create("/api/v1/specialist-profile"));
		return response.body(SpecialistProfileResponse.from(saved.profile()));
	}

	@PostMapping("/submit")
	ResponseEntity<SpecialistProfileResponse> submit(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "If-Match", required = false) String ifMatch) {
		var profile = profiles.submit(RequestIdentity.subject(jwt), RequestIdentity.requiredVersion(ifMatch));
		return response(profile);
	}

	private ResponseEntity<SpecialistProfileResponse> response(SpecialistProfileService.ProfileView profile) {
		return ResponseEntity.ok().eTag(Long.toString(profile.version()))
				.body(SpecialistProfileResponse.from(profile));
	}

	public record SpecialistProfileRequest(
			@NotBlank @Size(max = 120) String displayName,
			@NotBlank @Size(max = 2000) String bio,
			@NotEmpty @Size(max = 2) Set<@NotNull SupportArea> supportAreas,
			@NotEmpty @Size(max = 2) Set<@Pattern(regexp = "^(vi|en)$") String> languages,
			@Min(0) @Max(80) int yearsOfExperience,
			@NotBlank @Size(max = 64) String timezone) {

		SpecialistProfileService.ProfileCommand command() {
			return new SpecialistProfileService.ProfileCommand(displayName, bio, Set.copyOf(supportAreas),
					Set.copyOf(languages), yearsOfExperience, timezone);
		}
	}
}

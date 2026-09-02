package com.mentalbridge.care.profile;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

	private final ProfileService profiles;

	public ProfileController(ProfileService profiles) {
		this.profiles = profiles;
	}

	@GetMapping
	ResponseEntity<ProfileResponse> get(@AuthenticationPrincipal Jwt jwt) {
		var profile = profiles.get(subject(jwt));
		return ResponseEntity.ok().eTag(Long.toString(profile.version())).body(ProfileResponse.from(profile));
	}

	@PutMapping
	ResponseEntity<ProfileResponse> put(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@Valid @RequestBody ProfilePutRequest request) {
		var saved = profiles.put(subject(jwt), version(ifMatch), request.command());
		var response = ResponseEntity.status(saved.created() ? 201 : 200).eTag(Long.toString(saved.profile().version()));
		if (saved.created()) {
			response.location(URI.create("/api/v1/profile"));
		}
		return response.body(ProfileResponse.from(saved.profile()));
	}

	private Long version(String ifMatch) {
		if (ifMatch == null) return null;
		if (!ifMatch.matches("\"[0-9]+\"")) {
			throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_IF_MATCH",
					"If-Match must be a quoted non-negative profile version");
		}
		return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
	}

	private UUID subject(Jwt jwt) {
		try { return UUID.fromString(jwt.getSubject()); }
		catch (IllegalArgumentException exception) {
			throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
					"Authenticated account identifier is invalid");
		}
	}

	public record ProfilePutRequest(@NotBlank @Size(max = 120) String displayName, @Past LocalDate dateOfBirth,
			@Size(max = 32) String gender,
			@NotBlank @Size(max = 16) @Pattern(regexp = "^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$") String locale,
			@NotBlank @Size(max = 64) String timezone, boolean reminderEnabled) {
		ProfileService.ProfileCommand command() {
			return new ProfileService.ProfileCommand(displayName, dateOfBirth, gender, locale, timezone, reminderEnabled);
		}
	}

	public record ProfileResponse(UUID accountId, String displayName, LocalDate dateOfBirth, String gender,
			String locale, String timezone, boolean reminderEnabled, java.time.Instant createdAt,
			java.time.Instant updatedAt, long version) {
		static ProfileResponse from(ProfileService.ProfileView value) {
			return new ProfileResponse(value.accountId(), value.displayName(), value.dateOfBirth(), value.gender(),
					value.locale(), value.timezone(), value.reminderEnabled(), value.createdAt(), value.updatedAt(), value.version());
		}
	}
}

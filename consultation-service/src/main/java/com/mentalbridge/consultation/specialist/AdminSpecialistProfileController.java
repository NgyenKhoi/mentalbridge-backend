package com.mentalbridge.consultation.specialist;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

@Validated
@RestController
@RequestMapping("/api/v1/admin/specialist-profiles")
public class AdminSpecialistProfileController {

	private final SpecialistProfileService profiles;

	public AdminSpecialistProfileController(SpecialistProfileService profiles) {
		this.profiles = profiles;
	}

	@GetMapping
	PendingSpecialistProfilesResponse list(@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
		var items = profiles.listPending(limit).stream().map(SpecialistProfileResponse::from).toList();
		return new PendingSpecialistProfilesResponse(items, items.size());
	}

	@GetMapping("/{specialistAccountId}")
	ResponseEntity<SpecialistProfileResponse> detail(@PathVariable UUID specialistAccountId) {
		var profile = profiles.getForAdmin(specialistAccountId);
		return response(profile);
	}

	@PostMapping("/{specialistAccountId}/approve")
	ResponseEntity<SpecialistProfileResponse> approve(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID specialistAccountId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch) {
		var profile = profiles.approve(specialistAccountId, RequestIdentity.subject(jwt),
				RequestIdentity.requiredVersion(ifMatch));
		return response(profile);
	}

	private ResponseEntity<SpecialistProfileResponse> response(SpecialistProfileService.ProfileView profile) {
		return ResponseEntity.ok().eTag(Long.toString(profile.version()))
				.body(SpecialistProfileResponse.from(profile));
	}

	public record PendingSpecialistProfilesResponse(List<SpecialistProfileResponse> items, int count) {
	}
}

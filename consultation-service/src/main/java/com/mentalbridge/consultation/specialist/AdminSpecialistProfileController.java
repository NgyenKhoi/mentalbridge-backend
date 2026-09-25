package com.mentalbridge.consultation.specialist;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

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
	SpecialistProfilesResponse list(
			@RequestParam(defaultValue = "PENDING") SpecialistApprovalStatus status,
			@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
		var items = profiles.listForAdmin(status, limit).stream().map(SpecialistProfileResponse::from).toList();
		return new SpecialistProfilesResponse(items, items.size());
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

	@PostMapping("/{specialistAccountId}/reject")
	ResponseEntity<SpecialistProfileResponse> reject(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID specialistAccountId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@Valid @RequestBody DecisionReasonRequest request) {
		var profile = profiles.reject(specialistAccountId, RequestIdentity.subject(jwt),
				RequestIdentity.requiredVersion(ifMatch), request.reasonCode());
		return response(profile);
	}

	@PostMapping("/{specialistAccountId}/suspend")
	ResponseEntity<SpecialistSuspensionResponse> suspend(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID specialistAccountId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@Valid @RequestBody DecisionReasonRequest request) {
		var result = profiles.suspend(specialistAccountId, RequestIdentity.subject(jwt),
				RequestIdentity.requiredVersion(ifMatch), request.reasonCode());
		return ResponseEntity.ok().eTag(Long.toString(result.profile().version()))
				.body(SpecialistSuspensionResponse.from(result));
	}

	@PostMapping("/{specialistAccountId}/restore")
	ResponseEntity<SpecialistProfileResponse> restore(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID specialistAccountId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch) {
		var profile = profiles.restore(specialistAccountId, RequestIdentity.subject(jwt),
				RequestIdentity.requiredVersion(ifMatch));
		return response(profile);
	}

	private ResponseEntity<SpecialistProfileResponse> response(SpecialistProfileService.ProfileView profile) {
		return ResponseEntity.ok().eTag(Long.toString(profile.version()))
				.body(SpecialistProfileResponse.from(profile));
	}

	public record SpecialistProfilesResponse(List<SpecialistProfileResponse> items, int count) {
	}

	public record DecisionReasonRequest(@NotNull SpecialistDecisionReasonCode reasonCode) {
	}

	public record SpecialistSuspensionResponse(SpecialistProfileResponse profile, SuspensionEffects effects) {

		static SpecialistSuspensionResponse from(SpecialistProfileService.SuspensionResult result) {
			return new SpecialistSuspensionResponse(SpecialistProfileResponse.from(result.profile()),
					new SuspensionEffects(result.effects().withdrawnAvailabilitySlots(),
							result.effects().cancelledAppointments(), result.effects().releasedCredits()));
		}

		public record SuspensionEffects(int withdrawnAvailabilitySlots, int cancelledAppointments,
				int releasedCredits) {
		}
	}
}

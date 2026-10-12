package com.mentalbridge.consultation.specialist;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
@RequestMapping("/api/v1/admin/specialist-profiles/amendments")
public class AdminProfileAmendmentController {

	private final ProfileAmendmentService amendments;

	public AdminProfileAmendmentController(ProfileAmendmentService amendments) {
		this.amendments = amendments;
	}

	@GetMapping
	ProfileAmendmentService.Queue list(@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
			@RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page) {
		return amendments.list(limit, page);
	}

	@GetMapping("/{amendmentId}")
	ResponseEntity<ProfileAmendmentService.Detail> detail(@PathVariable UUID amendmentId) {
		var detail = amendments.detail(amendmentId);
		return ResponseEntity.ok().eTag(Long.toString(detail.amendment().version())).body(detail);
	}

	@PostMapping("/{amendmentId}/approve")
	ResponseEntity<ProfileAmendmentResponse> approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID amendmentId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch) {
		return response(amendments.approve(amendmentId, RequestIdentity.subject(jwt), RequestIdentity.requiredVersion(ifMatch)));
	}

	@PostMapping("/{amendmentId}/reject")
	ResponseEntity<ProfileAmendmentResponse> reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID amendmentId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@Valid @RequestBody AdminSpecialistProfileController.DecisionReasonRequest request) {
		return response(amendments.reject(amendmentId, RequestIdentity.subject(jwt),
				RequestIdentity.requiredVersion(ifMatch), request.reasonCode()));
	}

	private ResponseEntity<ProfileAmendmentResponse> response(ProfileAmendmentResponse value) {
		return ResponseEntity.ok().eTag(Long.toString(value.version())).body(value);
	}
}

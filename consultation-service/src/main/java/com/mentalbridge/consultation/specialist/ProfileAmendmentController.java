package com.mentalbridge.consultation.specialist;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
@RequestMapping("/api/v1/specialist-profile/amendments")
public class ProfileAmendmentController {

	private final ProfileAmendmentService amendments;

	public ProfileAmendmentController(ProfileAmendmentService amendments) {
		this.amendments = amendments;
	}

	@GetMapping("/current")
	ResponseEntity<ProfileAmendmentService.Detail> own(@AuthenticationPrincipal Jwt jwt) {
		var detail = amendments.own(RequestIdentity.subject(jwt));
		var version = detail.amendment() == null ? detail.approvedProfile().version() : detail.amendment().version();
		return ResponseEntity.ok().eTag(Long.toString(version)).body(detail);
	}

	@PostMapping
	ResponseEntity<ProfileAmendmentResponse> start(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "If-Match", required = false) String ifMatch) {
		return response(amendments.start(RequestIdentity.subject(jwt), RequestIdentity.requiredVersion(ifMatch)));
	}

	@PutMapping("/{amendmentId}")
	ResponseEntity<ProfileAmendmentResponse> edit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID amendmentId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@Valid @RequestBody SpecialistProfileController.SpecialistProfileRequest request) {
		return response(amendments.edit(RequestIdentity.subject(jwt), amendmentId,
				RequestIdentity.requiredVersion(ifMatch), request.command()));
	}

	@PostMapping("/{amendmentId}/submit")
	ResponseEntity<ProfileAmendmentResponse> submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID amendmentId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch) {
		return response(amendments.submit(RequestIdentity.subject(jwt), amendmentId,
				RequestIdentity.requiredVersion(ifMatch), false));
	}

	@PostMapping("/{amendmentId}/resubmit")
	ResponseEntity<ProfileAmendmentResponse> resubmit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID amendmentId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch) {
		return response(amendments.submit(RequestIdentity.subject(jwt), amendmentId,
				RequestIdentity.requiredVersion(ifMatch), true));
	}

	private ResponseEntity<ProfileAmendmentResponse> response(ProfileAmendmentResponse value) {
		return ResponseEntity.ok().eTag(Long.toString(value.version())).body(value);
	}
}

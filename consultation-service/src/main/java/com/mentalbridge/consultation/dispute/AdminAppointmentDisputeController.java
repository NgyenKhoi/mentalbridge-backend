package com.mentalbridge.consultation.dispute;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
public class AdminAppointmentDisputeController {

	private final AppointmentDisputeService disputes;

	public AdminAppointmentDisputeController(AppointmentDisputeService disputes) {
		this.disputes = disputes;
	}

	@GetMapping("/api/v1/admin/appointment-disputes")
	AppointmentDisputeResponse.ListResponse list(@RequestParam(defaultValue = "OPEN") String status) {
		return disputes.adminList(status);
	}

	@PostMapping("/api/v1/admin/appointment-disputes/{disputeId}/resolve")
	ResponseEntity<AppointmentDisputeResponse> resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID disputeId,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@RequestHeader("Idempotency-Key") @NotBlank @Size(min = 16, max = 128)
			@Pattern(regexp = "^[!-~]+$") String key,
			@Valid @RequestBody ResolveAppointmentDisputeRequest request) {
		var result = disputes.resolve(RequestIdentity.subject(jwt), disputeId,
				Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1)), key, request);
		return ResponseEntity.ok().eTag(Long.toString(result.version())).body(result);
	}
}

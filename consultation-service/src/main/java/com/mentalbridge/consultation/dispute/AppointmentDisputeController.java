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
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
public class AppointmentDisputeController {

	private final AppointmentDisputeService disputes;

	public AppointmentDisputeController(AppointmentDisputeService disputes) {
		this.disputes = disputes;
	}

	@GetMapping("/api/v1/appointments/{appointmentId}/dispute")
	AppointmentDisputeResponse userGet(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appointmentId) {
		return disputes.participant(RequestIdentity.subject(jwt), "USER", appointmentId);
	}

	@PostMapping("/api/v1/appointments/{appointmentId}/dispute")
	ResponseEntity<AppointmentDisputeResponse> userOpen(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID appointmentId, @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 16, max = 128)
			@Pattern(regexp = "^[!-~]+$") String key, @Valid @RequestBody OpenAppointmentDisputeRequest request) {
		return ResponseEntity.ok(disputes.open(RequestIdentity.subject(jwt), "USER", appointmentId, key, request));
	}

	@GetMapping("/api/v1/specialist/appointments/{appointmentId}/dispute")
	AppointmentDisputeResponse specialistGet(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appointmentId) {
		return disputes.participant(RequestIdentity.subject(jwt), "SPECIALIST", appointmentId);
	}

	@PostMapping("/api/v1/specialist/appointments/{appointmentId}/dispute")
	ResponseEntity<AppointmentDisputeResponse> specialistOpen(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID appointmentId, @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 16, max = 128)
			@Pattern(regexp = "^[!-~]+$") String key, @Valid @RequestBody OpenAppointmentDisputeRequest request) {
		return ResponseEntity.ok(disputes.open(RequestIdentity.subject(jwt), "SPECIALIST", appointmentId, key, request));
	}
}

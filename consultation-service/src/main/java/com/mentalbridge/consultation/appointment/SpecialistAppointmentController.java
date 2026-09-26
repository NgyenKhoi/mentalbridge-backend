package com.mentalbridge.consultation.appointment;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.ApiException;
import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
public class SpecialistAppointmentController {

	private final AppointmentDecisionService decisions;

	public SpecialistAppointmentController(AppointmentDecisionService decisions) {
		this.decisions = decisions;
	}

	@GetMapping("/api/v1/specialist/appointments")
	AppointmentResponse.ListResponse list(@AuthenticationPrincipal Jwt jwt) {
		return decisions.list(RequestIdentity.subject(jwt));
	}

	@PostMapping("/api/v1/specialist/appointments/{appointmentId}/accept")
	ResponseEntity<AppointmentResponse> accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appointmentId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@RequestHeader("Idempotency-Key") @NotBlank @Size(min = 16, max = 128)
			@Pattern(regexp = "^[!-~]+$") String idempotencyKey) {
		var result = decisions.accept(RequestIdentity.subject(jwt), appointmentId, version(ifMatch), idempotencyKey);
		return response(result);
	}

	@PostMapping("/api/v1/specialist/appointments/{appointmentId}/reject")
	ResponseEntity<AppointmentResponse> reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID appointmentId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@RequestHeader("Idempotency-Key") @NotBlank @Size(min = 16, max = 128)
			@Pattern(regexp = "^[!-~]+$") String idempotencyKey) {
		var result = decisions.reject(RequestIdentity.subject(jwt), appointmentId, version(ifMatch), idempotencyKey);
		return response(result);
	}

	private long version(String ifMatch) {
		if (ifMatch == null || !ifMatch.matches("\\\"[0-9]+\\\"")) {
			throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "APPOINTMENT_VERSION_REQUIRED",
					"If-Match must contain the quoted current appointment version");
		}
		return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
	}

	private ResponseEntity<AppointmentResponse> response(AppointmentResponse appointment) {
		return ResponseEntity.ok().eTag(Long.toString(appointment.version())).body(appointment);
	}
}

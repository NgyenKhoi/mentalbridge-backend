package com.mentalbridge.consultation.appointment;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.ApiException;
import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
public class AppointmentController {

	private final AppointmentService appointments;

	public AppointmentController(AppointmentService appointments) { this.appointments = appointments; }

	@GetMapping("/api/v1/bookable-slots")
	AppointmentResponse.BookableSlotList bookableSlots(@RequestParam(required = false) String from,
			@RequestParam(required = false) String to) {
		return appointments.bookableSlots(parse(from), parse(to));
	}

	@GetMapping("/api/v1/appointments")
	AppointmentResponse.ListResponse list(@AuthenticationPrincipal Jwt jwt) {
		return appointments.list(RequestIdentity.subject(jwt));
	}

	@PostMapping("/api/v1/appointments")
	ResponseEntity<AppointmentResponse> request(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") @NotBlank @Size(min = 16, max = 128)
			@Pattern(regexp = "^[!-~]+$") String idempotencyKey, @Valid @RequestBody RequestAppointment body) {
		var result = appointments.request(RequestIdentity.subject(jwt), idempotencyKey, body.slotId(), body.modality());
		return ResponseEntity.created(URI.create("/api/v1/appointments/" + result.id())).body(result);
	}

	private Instant parse(String value) {
		if (value == null) return null;
		try { return Instant.parse(value); }
		catch (DateTimeParseException exception) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BOOKABLE_SLOT_RANGE", "from and to must be UTC instants");
		}
	}

	public record RequestAppointment(@NotNull UUID slotId, @NotNull AppointmentModality modality) { }
}

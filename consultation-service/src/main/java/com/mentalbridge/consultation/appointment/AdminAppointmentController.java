package com.mentalbridge.consultation.appointment;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.ApiException;

@RestController
@RequestMapping("/api/v1/admin/appointments")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminAppointmentController {

	private final AdminAppointmentQueryService appointments;

	public AdminAppointmentController(AdminAppointmentQueryService appointments) {
		this.appointments = appointments;
	}

	@GetMapping
	AdminAppointmentResponse.Page search(@RequestParam(required = false) String status,
			@RequestParam(required = false) AppointmentModality modality, @RequestParam String from,
			@RequestParam String to, @RequestParam(required = false) UUID userAccountId,
			@RequestParam(required = false) UUID specialistAccountId,
			@RequestParam(required = false) @Size(max = 512) String cursor,
			@RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
		return appointments.search(status, modality, instant(from), instant(to), userAccountId,
				specialistAccountId, cursor, limit);
	}

	private Instant instant(String value) {
		try {
			return Instant.parse(value);
		}
		catch (DateTimeParseException exception) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ADMIN_APPOINTMENT_QUERY",
					"from and to must be UTC instants");
		}
	}
}

package com.mentalbridge.consultation.appointment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.ApiException;

@RestController
@RequestMapping("/internal/v1/appointments")
public class AppointmentNotificationQueryController {

	private final JdbcClient jdbc;
	private final AppointmentNotificationProperties properties;
	private final Clock clock;

	public AppointmentNotificationQueryController(JdbcClient jdbc, AppointmentNotificationProperties properties,
			Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.clock = clock;
	}

	@GetMapping("/{appointmentId}/notification-eligibility")
	public NotificationEligibility get(@PathVariable UUID appointmentId,
			@RequestParam long appointmentVersion,
			@RequestHeader(name = "X-MentalBridge-Service-Token", required = false) String serviceToken) {
		requireServiceToken(serviceToken);
		var row = jdbc.sql("""
				select id, user_account_id, version, status, scheduled_start_at, modality
				from appointment where id=:appointmentId
				""").param("appointmentId", appointmentId).query((result, ignored) -> new NotificationEligibility(
				result.getObject("id", UUID.class), result.getObject("user_account_id", UUID.class),
				result.getLong("version"), result.getString("status"),
				result.getTimestamp("scheduled_start_at").toInstant(),
				AppointmentModality.valueOf(result.getString("modality")), false)).optional()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND",
						"The appointment was not found"));
		var eligible = row.version() == appointmentVersion && row.status().equals("CONFIRMED")
				&& clock.instant().isBefore(row.scheduledStartAt());
		return new NotificationEligibility(row.appointmentId(), row.ownerAccountId(), row.version(), row.status(),
				row.scheduledStartAt(), row.modality(), eligible);
	}

	private void requireServiceToken(String supplied) {
		var expected = properties.serviceToken();
		if (supplied == null || expected == null || expected.isBlank()
				|| !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "SERVICE_AUTHENTICATION_REQUIRED",
					"Service authentication is required");
		}
	}

	public record NotificationEligibility(UUID appointmentId, UUID ownerAccountId, long version, String status,
			Instant scheduledStartAt, AppointmentModality modality, boolean eligible) {
	}
}

package com.mentalbridge.care.consultationbrief;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.mentalbridge.care.shared.ApiException;

import feign.FeignException;

@Component
public class AppointmentContextClient {

	private final ConsultationAppointmentHttpClient http;

	public AppointmentContextClient(ConsultationAppointmentHttpClient http) {
		this.http = http;
	}

	public AppointmentContext get(UUID appointmentId, String bearerToken, UUID correlationId) {
		if (bearerToken == null || bearerToken.isBlank() || correlationId == null) {
			throw new IllegalArgumentException("Authenticated appointment request context is required");
		}
		try {
			var context = http.context(appointmentId, "Bearer " + bearerToken, correlationId.toString());
			if (!valid(appointmentId, context)) throw unavailable();
			return context;
		}
		catch (FeignException.NotFound exception) {
			throw new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND", "The appointment was not found");
		}
		catch (ApiException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw unavailable();
		}
	}

	private boolean valid(UUID appointmentId, AppointmentContext context) {
		return context != null && appointmentId.equals(context.appointmentId())
				&& context.userAccountId() != null && context.specialistAccountId() != null
				&& context.status() != null && context.scheduledStartAt() != null
				&& context.scheduledEndAt() != null && context.scheduledEndAt().isAfter(context.scheduledStartAt())
				&& context.version() >= 0;
	}

	private ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "APPOINTMENT_CONTEXT_UNAVAILABLE",
				"Appointment authority could not be verified");
	}
}

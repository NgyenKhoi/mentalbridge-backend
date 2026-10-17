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

	public SpecialistClientRelationshipProjection clientRelationships(String bearerToken, UUID correlationId) {
		if (bearerToken == null || bearerToken.isBlank() || correlationId == null) {
			throw new IllegalArgumentException("Authenticated relationship request context is required");
		}
		try {
			var projection = http.clientRelationships("Bearer " + bearerToken, correlationId.toString());
			if (projection == null || projection.items() == null || projection.generatedAt() == null
					|| projection.recentSince() == null || projection.policyVersion() == null
					|| projection.count() != projection.items().size()
					|| projection.items().stream().anyMatch(this::invalid)) throw unavailable();
			return projection;
		}
		catch (ApiException exception) {
			throw exception;
		}
		catch (FeignException.Forbidden exception) {
			throw new ApiException(HttpStatus.FORBIDDEN, "SPECIALIST_CONTINUITY_ACCESS_DENIED",
					"Specialist continuity access is unavailable");
		}
		catch (RuntimeException exception) {
			throw unavailable();
		}
	}

	private boolean invalid(SpecialistClientRelationshipProjection.Item item) {
		return item == null || item.appointmentId() == null || item.userAccountId() == null
				|| item.status() == null || item.modality() == null || item.scheduledStartAt() == null
				|| item.scheduledEndAt() == null || !item.scheduledEndAt().isAfter(item.scheduledStartAt())
				|| item.appointmentVersion() < 0;
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

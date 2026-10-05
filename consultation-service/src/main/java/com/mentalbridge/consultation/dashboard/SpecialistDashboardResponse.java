package com.mentalbridge.consultation.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SpecialistDashboardResponse(String source, Instant generatedAt,
		OperationalStatus operationalStatus, Profile profile, RatingAggregate ratingAggregate,
		AppointmentCollection todayConfirmedSessions,
		AppointmentCollection pendingAppointmentRequests, NextAppointment nextAppointment,
		AvailabilityCollection availability, List<ActionItem> actionRequired) {

	public enum DataState {
		AVAILABLE,
		EMPTY,
		BLOCKED,
		UNAVAILABLE
	}

	public enum OperationalStatus {
		READY,
		PROFILE_REQUIRED,
		PENDING_APPROVAL,
		PROFILE_REJECTED,
		SUSPENDED
	}

	public enum ActionType {
		COMPLETE_PROFILE,
		AWAIT_PROFILE_APPROVAL,
		UPDATE_REJECTED_PROFILE,
		CONTACT_SUPPORT,
		REVIEW_APPOINTMENT_REQUESTS,
		PUBLISH_AVAILABILITY
	}

	public record Profile(String source, Instant asOf, DataState state, String displayName,
			String timezone, String approvalStatus) {
	}

	public record RatingAggregate(String source, Instant asOf, DataState state,
			BigDecimal averageRating, long ratingCount) {
	}

	public record AppointmentCollection(String source, Instant asOf, DataState state, int count,
			LocalDate localDate, String timezone, List<AppointmentItem> items) {
	}

	public record NextAppointment(String source, Instant asOf, DataState state, AppointmentItem item) {
	}

	public record AppointmentItem(String source, Instant asOf, UUID appointmentId, String status,
			String modality, Instant scheduledStartAt, Instant scheduledEndAt, String timezone,
			Instant decisionDeadlineAt) {
	}

	public record AvailabilityCollection(String source, Instant asOf, DataState state, int count,
			List<AvailabilityItem> items) {
	}

	public record AvailabilityItem(String source, Instant asOf, UUID slotId, String modality,
			Instant startAt, Instant endAt, String timezone) {
	}

	public record ActionItem(String source, Instant asOf, ActionType type, int count) {
	}
}

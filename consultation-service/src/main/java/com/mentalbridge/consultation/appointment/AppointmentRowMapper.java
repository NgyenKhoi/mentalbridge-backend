package com.mentalbridge.consultation.appointment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

final class AppointmentRowMapper {

	private AppointmentRowMapper() {
	}

	static AppointmentResponse map(ResultSet row, int ignored) throws SQLException {
		var decidedAt = row.getTimestamp("decided_at");
		var cancelledAt = row.getTimestamp("cancelled_at");
		var sessionEndedAt = row.getTimestamp("session_ended_at");
		var sessionSettledAt = row.getTimestamp("session_settled_at");
		return new AppointmentResponse(row.getObject("id", UUID.class), row.getObject("availability_slot_id", UUID.class),
				row.getObject("specialist_account_id", UUID.class), row.getString("display_name"), row.getString("status"),
				AppointmentModality.valueOf(row.getString("modality")), row.getTimestamp("scheduled_start_at").toInstant(),
				row.getTimestamp("scheduled_end_at").toInstant(), row.getString("display_timezone"),
				row.getTimestamp("requested_at").toInstant(), row.getTimestamp("decision_deadline_at").toInstant(),
				row.getObject("service_credit_id", UUID.class), row.getObject("replaces_appointment_id", UUID.class),
				row.getObject("replaced_by_appointment_id", UUID.class),
				decidedAt == null ? null : decidedAt.toInstant(), row.getString("decision_reason"),
				cancelledAt == null ? null : cancelledAt.toInstant(), row.getString("cancellation_reason"),
				actorType(row.getString("cancellation_reason")), row.getString("cancellation_credit_outcome"),
				row.getString("session_outcome"), row.getString("session_outcome_reason"),
				row.getString("session_policy_version"), sessionEndedAt == null ? null : sessionEndedAt.toInstant(),
				sessionSettledAt == null ? null : sessionSettledAt.toInstant(),
				row.getObject("completion_fact_id", UUID.class),
				row.getString("credit_state"), java.util.List.of(), row.getLong("version"));
	}

	static String actorType(String reason) {
		if (reason == null) return null;
		if (reason.startsWith("USER_") || reason.equals("APPOINTMENT_REQUESTED")) return "USER";
		if (reason.equals("SPECIALIST_SUSPENDED")) return "ADMIN";
		if (reason.startsWith("SPECIALIST_")) return "SPECIALIST";
		if (reason.equals("DECISION_DEADLINE_EXPIRED") || reason.startsWith("SESSION_")
				|| reason.equals("SCHEDULED_WINDOW_ENDED") || reason.equals("EVIDENCE_REQUIREMENTS_MET")) return "SYSTEM";
		return "ADMIN";
	}
}

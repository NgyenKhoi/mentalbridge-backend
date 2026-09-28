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
				row.getString("credit_state"), java.util.List.of(), row.getLong("version"));
	}

	static String actorType(String reason) {
		if (reason == null) return null;
		if (reason.startsWith("USER_") || reason.equals("APPOINTMENT_REQUESTED")) return "USER";
		if (reason.equals("SPECIALIST_SUSPENDED")) return "ADMIN";
		if (reason.startsWith("SPECIALIST_")) return "SPECIALIST";
		if (reason.equals("DECISION_DEADLINE_EXPIRED")) return "SYSTEM";
		return "ADMIN";
	}
}

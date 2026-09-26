package com.mentalbridge.consultation.appointment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

final class AppointmentRowMapper {

	private AppointmentRowMapper() {
	}

	static AppointmentResponse map(ResultSet row, int ignored) throws SQLException {
		var decidedAt = row.getTimestamp("decided_at");
		return new AppointmentResponse(row.getObject("id", UUID.class), row.getObject("availability_slot_id", UUID.class),
				row.getObject("specialist_account_id", UUID.class), row.getString("display_name"), row.getString("status"),
				AppointmentModality.valueOf(row.getString("modality")), row.getTimestamp("scheduled_start_at").toInstant(),
				row.getTimestamp("scheduled_end_at").toInstant(), row.getString("display_timezone"),
				row.getTimestamp("requested_at").toInstant(), row.getTimestamp("decision_deadline_at").toInstant(),
				row.getObject("service_credit_id", UUID.class), row.getObject("replaces_appointment_id", UUID.class),
				decidedAt == null ? null : decidedAt.toInstant(), row.getString("decision_reason"),
				row.getString("credit_state"), row.getLong("version"));
	}
}

package com.mentalbridge.consultation.appointment;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.shared.ApiException;

@Service
public class ConsultationBriefAppointmentContextService {

	private final JdbcClient jdbc;

	public ConsultationBriefAppointmentContextService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public ConsultationBriefAppointmentContext read(UUID appointmentId, UUID actorId,
			boolean user, boolean specialist) {
		return jdbc.sql("""
				select id, user_account_id, specialist_account_id, status,
				       scheduled_start_at, scheduled_end_at, version
				from appointment
				where id=:appointmentId
				  and ((:userRole and user_account_id=:actorId)
				       or (:specialistRole and specialist_account_id=:actorId))
				""")
				.param("appointmentId", appointmentId)
				.param("actorId", actorId)
				.param("userRole", user)
				.param("specialistRole", specialist)
				.query((rs, row) -> new ConsultationBriefAppointmentContext(
						rs.getObject("id", UUID.class), rs.getObject("user_account_id", UUID.class),
						rs.getObject("specialist_account_id", UUID.class), rs.getString("status"),
						rs.getTimestamp("scheduled_start_at").toInstant(),
						rs.getTimestamp("scheduled_end_at").toInstant(), rs.getLong("version")))
				.optional()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND",
						"The appointment was not found"));
	}
}

package com.mentalbridge.consultation.appointment;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AppointmentStatusOutbox {

	private final JdbcClient jdbc;

	public AppointmentStatusOutbox(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void record(UUID appointmentId, UUID correlationId, Instant occurredAt) {
		jdbc.sql("""
				insert into appointment_outbox_event (
				 id, appointment_id, appointment_version, correlation_id, payload,
				 occurred_at, created_at
				)
				select :id, appointment.id, appointment.version, :correlationId,
				 jsonb_build_object(
				   'appointmentId', appointment.id::text,
				   'ownerAccountId', appointment.user_account_id::text,
				   'status', appointment.status,
				   'scheduledStartAt', to_char(appointment.scheduled_start_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
				   'modality', appointment.modality,
				   'replacesAppointmentId', appointment.replaces_appointment_id::text
				 ), :occurredAt, :occurredAt
				from appointment where appointment.id=:appointmentId
				  and appointment.modality in ('IN_APP_CHAT', 'IN_APP_VIDEO')
				on conflict (appointment_id, appointment_version) do nothing
				""").param("id", UUID.randomUUID()).param("appointmentId", appointmentId)
				.param("correlationId", correlationId).param("occurredAt", database(occurredAt)).update();
	}

	private OffsetDateTime database(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}

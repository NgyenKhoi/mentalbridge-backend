package com.mentalbridge.consultation.specialist;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mentalbridge.consultation.shared.ApiException;

@Service
class SpecialistSuspensionEffects {

	private static final String CANCELLATION_REASON = "SPECIALIST_SUSPENDED";

	private final JdbcClient jdbc;

	SpecialistSuspensionEffects(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Effects apply(UUID specialistAccountId, UUID adminAccountId, Instant now) {
		var withdrawnSlots = jdbc.sql("""
				update availability_slot set status='WITHDRAWN', withdrawn_at=:now,
				updated_at=:now, version=version+1
				where specialist_account_id=:specialistId and status='ACTIVE' and start_at > :now
				""").param("specialistId", specialistAccountId).param("now", databaseInstant(now)).update();
		var appointments = jdbc.sql("""
				select id, service_credit_id, user_account_id, status from appointment
				where specialist_account_id=:specialistId and scheduled_start_at > :now
				and status in ('REQUESTED', 'CONFIRMED')
				order by scheduled_start_at, id for update
				""").param("specialistId", specialistAccountId).param("now", databaseInstant(now))
				.query((row, ignored) -> new AppointmentHold(row.getObject("id", UUID.class),
						row.getObject("service_credit_id", UUID.class), row.getObject("user_account_id", UUID.class),
						row.getString("status"))).list();
		for (var appointment : appointments) cancelAndRelease(appointment, adminAccountId, now);
		return new Effects(withdrawnSlots, appointments.size(), appointments.size());
	}

	private void cancelAndRelease(AppointmentHold appointment, UUID adminAccountId, Instant now) {
		var cancelled = jdbc.sql("""
				update appointment set status='CANCELLED', cancellation_reason=:reason,
				cancelled_at=:now, updated_at=:now, version=version+1
				where id=:appointmentId and status in ('REQUESTED', 'CONFIRMED')
				""").param("reason", CANCELLATION_REASON).param("now", databaseInstant(now))
				.param("appointmentId", appointment.id()).update();
		if (cancelled != 1) throw consistencyConflict();

		var released = jdbc.sql("""
				update service_credit credit set state='AVAILABLE', appointment_id=null,
				updated_at=:now, version=version+1
				where credit.id=:creditId and credit.state='HELD'
				and credit.appointment_id=:appointmentId
				and exists (
				    select 1 from service_credit_period period
				    where period.id=credit.period_id and period.account_id=:userId
				)
				""").param("now", databaseInstant(now)).param("creditId", appointment.creditId())
				.param("appointmentId", appointment.id()).param("userId", appointment.userId()).update();
		if (released != 1) throw consistencyConflict();

		jdbc.sql("""
				insert into service_credit_ledger (
				    id, credit_id, account_id, event_type, appointment_id, idempotency_key, occurred_at
				) values (:id, :creditId, :accountId, 'RELEASED', :appointmentId, :key, :now)
				""").param("id", UUID.randomUUID()).param("creditId", appointment.creditId())
				.param("accountId", appointment.userId()).param("appointmentId", appointment.id())
				.param("key", "specialist-suspension:" + appointment.id()).param("now", databaseInstant(now)).update();

		jdbc.sql("""
				insert into appointment_status_history (
				    id, appointment_id, from_status, to_status, changed_by, reason, changed_at
				) values (:id, :appointmentId, :fromStatus, 'CANCELLED', :changedBy, :reason, :now)
				""").param("id", UUID.randomUUID()).param("appointmentId", appointment.id())
				.param("fromStatus", appointment.status()).param("changedBy", adminAccountId)
				.param("reason", CANCELLATION_REASON).param("now", databaseInstant(now)).update();
	}

	private ApiException consistencyConflict() {
		return new ApiException(HttpStatus.CONFLICT, "SPECIALIST_SUSPENSION_CREDIT_CONFLICT",
				"A future appointment credit could not be released safely");
	}

	private OffsetDateTime databaseInstant(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	record Effects(int withdrawnAvailabilitySlots, int cancelledAppointments, int releasedCredits) {
		static Effects none() {
			return new Effects(0, 0, 0);
		}
	}

	private record AppointmentHold(UUID id, UUID creditId, UUID userId, String status) {
	}
}

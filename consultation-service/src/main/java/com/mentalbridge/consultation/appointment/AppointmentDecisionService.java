package com.mentalbridge.consultation.appointment;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.credits.CreditEventType;
import com.mentalbridge.consultation.credits.ServiceCreditService;
import com.mentalbridge.consultation.shared.ApiException;

@Service
public class AppointmentDecisionService {

	private static final int LIST_LIMIT = 100;
	private static final int EXPIRY_BATCH_SIZE = 100;
	private static final String ACCEPTED = "SPECIALIST_ACCEPTED";
	private static final String REJECTED = "SPECIALIST_REJECTED";
	private static final String EXPIRED = "DECISION_DEADLINE_EXPIRED";

	private final JdbcClient jdbc;
	private final ServiceCreditService credits;
	private final Clock clock;

	public AppointmentDecisionService(JdbcClient jdbc, ServiceCreditService credits, Clock clock) {
		this.jdbc = jdbc;
		this.credits = credits;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public AppointmentResponse.ListResponse list(UUID specialistId) {
		var items = jdbc.sql("""
				select a.*, p.display_name, c.state as credit_state from appointment a
				join specialist_profile p on p.account_id=a.specialist_account_id
				join service_credit c on c.id=a.service_credit_id
				where a.specialist_account_id=:specialistId
				order by case when a.status='REQUESTED' then 0 else 1 end,
				         a.decision_deadline_at, a.scheduled_start_at, a.id
				limit :limit
				""").param("specialistId", specialistId).param("limit", LIST_LIMIT)
				.query(AppointmentRowMapper::map).list();
		return new AppointmentResponse.ListResponse(items, items.size(), clock.instant());
	}

	@Transactional
	public AppointmentResponse accept(UUID specialistId, UUID appointmentId, long expectedVersion,
			String idempotencyKey) {
		return decide(specialistId, appointmentId, expectedVersion, idempotencyKey, "CONFIRMED", ACCEPTED, false);
	}

	@Transactional
	public AppointmentResponse reject(UUID specialistId, UUID appointmentId, long expectedVersion,
			String idempotencyKey) {
		return decide(specialistId, appointmentId, expectedVersion, idempotencyKey, "REJECTED", REJECTED, true);
	}

	@Transactional(readOnly = true)
	public List<UUID> dueRequestIds() {
		return jdbc.sql("""
				select id from appointment
				where status='REQUESTED' and decision_deadline_at<=:now
				order by decision_deadline_at, id limit :limit
				""").param("now", database(clock.instant())).param("limit", EXPIRY_BATCH_SIZE)
				.query(UUID.class).list();
	}

	@Transactional
	public boolean expire(UUID appointmentId) {
		var appointment = lock(appointmentId);
		if (!appointment.status().equals("REQUESTED")) return false;
		var now = clock.instant();
		if (appointment.deadline().isAfter(now)) return false;
		assertHeld(appointment);
		jdbc.sql("""
				update appointment set status='EXPIRED', decided_at=:now, decision_reason=:reason,
				updated_at=:now, version=version+1
				where id=:id and status='REQUESTED' and version=:version
				""").param("now", database(now)).param("reason", EXPIRED).param("id", appointment.id())
				.param("version", appointment.version()).update();
		credits.transition(appointment.userId(), appointment.creditId(), appointment.id(), CreditEventType.RELEASED,
				"appointment-expiry:" + appointment.id());
		insertHistory(appointment, null, "EXPIRED", EXPIRED, "appointment-expiry:" + appointment.id(), now);
		return true;
	}

	private AppointmentResponse decide(UUID specialistId, UUID appointmentId, long expectedVersion,
			String idempotencyKey, String nextStatus, String reason, boolean releaseCredit) {
		var replay = command(appointmentId, idempotencyKey, specialistId);
		if (replay != null) {
			if (!replay.toStatus().equals(nextStatus) || !replay.reason().equals(reason)) {
				throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used for another appointment decision");
			}
			return find(appointmentId, specialistId);
		}
		var appointment = lock(appointmentId);
		if (!appointment.specialistId().equals(specialistId)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "APPOINTMENT_NOT_ASSIGNED",
					"Only the assigned specialist can decide this appointment request");
		}
		if (appointment.version() != expectedVersion) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "APPOINTMENT_VERSION_MISMATCH",
					"The appointment version is stale");
		}
		if (!appointment.status().equals("REQUESTED")) {
			throw conflict("APPOINTMENT_NOT_DECISION_ELIGIBLE", "The appointment request is no longer decision-eligible");
		}
		var now = clock.instant();
		if (!now.isBefore(appointment.deadline())) {
			throw conflict("APPOINTMENT_DECISION_DEADLINE_PASSED", "The appointment decision deadline has passed");
		}
		assertHeld(appointment);
		jdbc.sql("""
				update appointment set status=:status, decided_at=:now, decision_reason=:reason,
				updated_at=:now, version=version+1
				where id=:id and status='REQUESTED' and version=:version
				""").param("status", nextStatus).param("now", database(now)).param("reason", reason)
				.param("id", appointment.id()).param("version", appointment.version()).update();
		if (releaseCredit) {
			credits.transition(appointment.userId(), appointment.creditId(), appointment.id(), CreditEventType.RELEASED,
					"appointment-rejection:" + appointment.id());
		}
		insertHistory(appointment, specialistId, nextStatus, reason, idempotencyKey, now);
		return find(appointmentId, specialistId);
	}

	private AppointmentHold lock(UUID appointmentId) {
		return jdbc.sql("""
				select a.id, a.user_account_id, a.specialist_account_id, a.service_credit_id,
				       a.status, a.decision_deadline_at, a.version,
				       c.state as credit_state, c.appointment_id as credit_appointment_id
				from appointment a join service_credit c on c.id=a.service_credit_id
				where a.id=:id for update of a, c
				""").param("id", appointmentId).query((row, ignored) -> new AppointmentHold(
					row.getObject("id", UUID.class), row.getObject("user_account_id", UUID.class),
					row.getObject("specialist_account_id", UUID.class), row.getObject("service_credit_id", UUID.class),
					row.getString("status"), row.getTimestamp("decision_deadline_at").toInstant(), row.getLong("version"),
					row.getString("credit_state"), row.getObject("credit_appointment_id", UUID.class)))
				.optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND",
						"The appointment was not found"));
	}

	private ExistingCommand command(UUID appointmentId, String key, UUID specialistId) {
		return jdbc.sql("""
				select h.to_status, h.reason from appointment_status_history h
				join appointment a on a.id=h.appointment_id
				where h.appointment_id=:appointmentId and h.idempotency_key=:key
				  and h.changed_by=:specialistId and a.specialist_account_id=:specialistId
				""").param("appointmentId", appointmentId).param("key", key).param("specialistId", specialistId)
				.query((row, ignored) -> new ExistingCommand(row.getString("to_status"), row.getString("reason")))
				.optional().orElse(null);
	}

	private AppointmentResponse find(UUID appointmentId, UUID specialistId) {
		return jdbc.sql("""
				select a.*, p.display_name, c.state as credit_state from appointment a
				join specialist_profile p on p.account_id=a.specialist_account_id
				join service_credit c on c.id=a.service_credit_id
				where a.id=:appointmentId and a.specialist_account_id=:specialistId
				""").param("appointmentId", appointmentId).param("specialistId", specialistId)
				.query(AppointmentRowMapper::map).single();
	}

	private void assertHeld(AppointmentHold appointment) {
		if (!appointment.creditState().equals("HELD") || !appointment.id().equals(appointment.creditAppointmentId())) {
			throw conflict("APPOINTMENT_CREDIT_CONFLICT", "The appointment credit is not held consistently");
		}
	}

	private void insertHistory(AppointmentHold appointment, UUID actor, String nextStatus, String reason,
			String idempotencyKey, Instant now) {
		jdbc.sql("""
				insert into appointment_status_history (
				 id, appointment_id, from_status, to_status, changed_by, reason, idempotency_key, changed_at
				) values (:id, :appointmentId, :fromStatus, :toStatus, :changedBy, :reason, :key, :now)
				""").param("id", UUID.randomUUID()).param("appointmentId", appointment.id())
				.param("fromStatus", appointment.status()).param("toStatus", nextStatus).param("changedBy", actor)
				.param("reason", reason).param("key", idempotencyKey).param("now", database(now)).update();
	}

	private OffsetDateTime database(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private ApiException conflict(String code, String message) {
		return new ApiException(HttpStatus.CONFLICT, code, message);
	}

	private record AppointmentHold(UUID id, UUID userId, UUID specialistId, UUID creditId, String status,
			Instant deadline, long version, String creditState, UUID creditAppointmentId) {
	}

	private record ExistingCommand(String toStatus, String reason) {
	}
}

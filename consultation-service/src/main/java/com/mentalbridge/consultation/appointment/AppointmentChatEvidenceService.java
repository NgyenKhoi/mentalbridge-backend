package com.mentalbridge.consultation.appointment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.shared.ApiException;

@Service
public class AppointmentChatEvidenceService {

	private static final Duration WAITING_WINDOW = Duration.ofMinutes(10);
	private static final Duration LATE_EVIDENCE_GRACE = Duration.ofMinutes(5);
	private static final Duration FINAL_DEADLINE = Duration.ofMinutes(35);
	private static final Duration MAX_PRESENCE_INTERVAL = Duration.ofSeconds(60);

	private final JdbcClient jdbc;
	private final Clock clock;

	public AppointmentChatEvidenceService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional
	public ChatEvidenceResponse record(UUID actorId, String actorRole, UUID appointmentId,
			ChatEvidenceRequest request) {
		var receivedAt = clock.instant();
		var appointment = findForUpdate(appointmentId);
		if (!participantMatches(appointment, actorId, actorRole)) throw notFound();
		if (appointment.modality() != AppointmentModality.IN_APP_CHAT || !appointment.wasConfirmed()) {
			return response(request, false, false, "APPOINTMENT_NOT_ELIGIBLE", receivedAt);
		}
		var existing = findEvidence(appointmentId, request.evidenceId());
		if (existing != null) {
			if (existing.matches(actorId, actorRole, request)) {
				return response(request, true, true, "EVIDENCE_ALREADY_ACCEPTED", receivedAt);
			}
			markEvidenceFailure(appointmentId, receivedAt);
			return response(request, false, false, "EVIDENCE_ID_CONFLICT", receivedAt);
		}
		var receiptDeadline = appointment.evidenceFailureReason() == null
				? appointment.end().plus(LATE_EVIDENCE_GRACE) : appointment.end().plus(FINAL_DEADLINE);
		if (appointment.sessionOutcome() != null || receivedAt.isAfter(receiptDeadline)) {
			return response(request, false, false, "EVIDENCE_WINDOW_CLOSED", receivedAt);
		}
		var validation = validate(appointment, request);
		if (validation != null) return response(request, false, false, validation, receivedAt);

		jdbc.sql("""
				insert into appointment_chat_evidence (
				    id, appointment_id, evidence_id, participant_account_id, participant_role,
				    evidence_type, interval_started_at, message_id, occurred_at, received_at
				) values (
				    :id, :appointmentId, :evidenceId, :actorId, :actorRole,
				    :type, :intervalStart, :messageId, :occurredAt, :receivedAt
				)
				""").param("id", UUID.randomUUID()).param("appointmentId", appointmentId)
				.param("evidenceId", request.evidenceId()).param("actorId", actorId).param("actorRole", actorRole)
				.param("type", request.type().name()).param("intervalStart", databaseInstant(request.intervalStartedAt()))
				.param("messageId", request.messageId()).param("occurredAt", databaseInstant(request.occurredAt()))
				.param("receivedAt", databaseInstant(receivedAt)).update();
		if (appointment.evidenceFailureReason() != null) {
			clearEvidenceFailure(appointmentId, receivedAt);
		}
		if (request.type() != ChatEvidenceRequest.Type.CHECK_IN && appointment.status().equals("CONFIRMED")
				&& receivedAt.isBefore(appointment.end())) {
			startSession(appointmentId, receivedAt);
		}
		return response(request, true, false, "EVIDENCE_ACCEPTED", receivedAt);
	}

	private AppointmentEvidenceTarget findForUpdate(UUID appointmentId) {
		return jdbc.sql("""
				select a.id, a.user_account_id, a.specialist_account_id, a.status, a.modality,
				       a.scheduled_start_at, a.scheduled_end_at, a.session_outcome,
				       a.evidence_failure_reason,
				       exists(select 1 from appointment_status_history h
				              where h.appointment_id=a.id and h.to_status='CONFIRMED') as was_confirmed
				from appointment a where a.id=:id for update
				""").param("id", appointmentId).query((row, ignored) -> new AppointmentEvidenceTarget(
				row.getObject("id", UUID.class), row.getObject("user_account_id", UUID.class),
				row.getObject("specialist_account_id", UUID.class), row.getString("status"),
				AppointmentModality.valueOf(row.getString("modality")),
				row.getTimestamp("scheduled_start_at").toInstant(), row.getTimestamp("scheduled_end_at").toInstant(),
				row.getString("session_outcome"), row.getString("evidence_failure_reason"),
				row.getBoolean("was_confirmed"))).optional().orElseThrow(this::notFound);
	}

	private ExistingEvidence findEvidence(UUID appointmentId, UUID evidenceId) {
		return jdbc.sql("""
				select participant_account_id, participant_role, evidence_type, interval_started_at,
				       message_id, occurred_at
				from appointment_chat_evidence where appointment_id=:appointmentId and evidence_id=:evidenceId
				""").param("appointmentId", appointmentId).param("evidenceId", evidenceId)
				.query((row, ignored) -> new ExistingEvidence(row.getObject("participant_account_id", UUID.class),
						row.getString("participant_role"), ChatEvidenceRequest.Type.valueOf(row.getString("evidence_type")),
						instant(row, "interval_started_at"), row.getObject("message_id", UUID.class),
						row.getTimestamp("occurred_at").toInstant())).optional().orElse(null);
	}

	private String validate(AppointmentEvidenceTarget appointment, ChatEvidenceRequest request) {
		if (appointment.status().equals("CANCELLED") || appointment.status().equals("REJECTED")
				|| appointment.status().equals("EXPIRED")) return "APPOINTMENT_NOT_ELIGIBLE";
		var earliest = request.type() == ChatEvidenceRequest.Type.CHECK_IN
				? appointment.start().minus(WAITING_WINDOW) : appointment.start();
		if (request.occurredAt().isBefore(earliest) || !request.occurredAt().isBefore(appointment.end())) {
			return "EVIDENCE_OCCURRED_OUTSIDE_WINDOW";
		}
		return switch (request.type()) {
			case CHECK_IN -> request.intervalStartedAt() == null && request.messageId() == null
					? null : "INVALID_EVIDENCE_SHAPE";
			case ACCEPTED_MESSAGE -> request.intervalStartedAt() == null && request.messageId() != null
					? null : "INVALID_EVIDENCE_SHAPE";
			case PRESENCE_INTERVAL -> validPresence(request)
					? null : "INVALID_PRESENCE_INTERVAL";
		};
	}

	private boolean validPresence(ChatEvidenceRequest request) {
		var start = request.intervalStartedAt();
		return start != null && request.messageId() == null && start.isBefore(request.occurredAt())
				&& Duration.between(start, request.occurredAt()).compareTo(MAX_PRESENCE_INTERVAL) <= 0;
	}

	private void startSession(UUID appointmentId, Instant now) {
		jdbc.sql("""
				update appointment set status='IN_PROGRESS', updated_at=:now, version=version+1
				where id=:id and status='CONFIRMED'
				""").param("now", databaseInstant(now)).param("id", appointmentId).update();
		jdbc.sql("""
				insert into appointment_status_history (
				    id, appointment_id, from_status, to_status, changed_by, reason, idempotency_key, changed_at
				) values (:eventId, :id, 'CONFIRMED', 'IN_PROGRESS', null,
				          'SESSION_ACTIVITY_OBSERVED', :key, :now)
				on conflict (appointment_id, idempotency_key) where idempotency_key is not null do nothing
				""").param("eventId", UUID.randomUUID()).param("id", appointmentId)
				.param("key", "session-start:" + appointmentId).param("now", databaseInstant(now)).update();
	}

	private void markEvidenceFailure(UUID appointmentId, Instant now) {
		jdbc.sql("""
				update appointment set evidence_failure_reason='EVIDENCE_ID_CONFLICT', updated_at=:now,
				version=version+1 where id=:id and session_outcome is null
				""").param("now", databaseInstant(now)).param("id", appointmentId).update();
	}

	private void clearEvidenceFailure(UUID appointmentId, Instant now) {
		jdbc.sql("""
				update appointment set evidence_failure_reason=null, updated_at=:now,
				version=version+1 where id=:id and session_outcome is null
				""").param("now", databaseInstant(now)).param("id", appointmentId).update();
	}

	private boolean participantMatches(AppointmentEvidenceTarget appointment, UUID actorId, String actorRole) {
		return actorRole.equals("USER") && appointment.userId().equals(actorId)
				|| actorRole.equals("SPECIALIST") && appointment.specialistId().equals(actorId);
	}

	private ChatEvidenceResponse response(ChatEvidenceRequest request, boolean accepted, boolean duplicate,
			String reason, Instant receivedAt) {
		return new ChatEvidenceResponse(request.evidenceId(), accepted, duplicate, reason, receivedAt);
	}

	private Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private OffsetDateTime databaseInstant(Instant value) {
		return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
	}

	private ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_CHAT_NOT_FOUND", "The appointment chat was not found");
	}

	private record AppointmentEvidenceTarget(UUID id, UUID userId, UUID specialistId, String status,
			AppointmentModality modality, Instant start, Instant end, String sessionOutcome,
			String evidenceFailureReason, boolean wasConfirmed) { }

	private record ExistingEvidence(UUID actorId, String actorRole, ChatEvidenceRequest.Type type,
			Instant intervalStart, UUID messageId, Instant occurredAt) {
		boolean matches(UUID expectedActorId, String expectedRole, ChatEvidenceRequest request) {
			return actorId.equals(expectedActorId) && actorRole.equals(expectedRole) && type == request.type()
					&& sameInstant(intervalStart, request.intervalStartedAt())
					&& java.util.Objects.equals(messageId, request.messageId())
					&& sameInstant(occurredAt, request.occurredAt());
		}

		private boolean sameInstant(Instant left, Instant right) {
			if (left == null || right == null) return left == right;
			return Duration.between(left, right).abs().compareTo(Duration.ofNanos(1_000)) <= 0;
		}
	}
}

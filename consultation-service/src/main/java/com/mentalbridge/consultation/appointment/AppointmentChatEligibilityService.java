package com.mentalbridge.consultation.appointment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.shared.ApiException;

@Service
public class AppointmentChatEligibilityService {

	private static final Duration WAITING_WINDOW = Duration.ofMinutes(10);

	private final JdbcClient jdbc;
	private final Clock clock;

	public AppointmentChatEligibilityService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public AppointmentChatEligibility decide(UUID actorId, String actorRole, UUID conversationId,
			AppointmentChatEligibility.Operation operation) {
		var appointment = jdbc.sql("""
				select a.id, a.user_account_id, a.specialist_account_id, a.status, a.modality,
				       a.scheduled_start_at, a.scheduled_end_at, a.session_outcome,
				       a.evidence_failure_reason, c.state as credit_state,
				       exists(select 1 from appointment_chat_evidence e
				              where e.appointment_id=a.id and e.participant_account_id=:actorId
				                and e.evidence_type='CHECK_IN') as participant_checked_in,
				       exists(select 1 from appointment replacement where replacement.replaces_appointment_id=a.id) as was_rescheduled,
				       exists(select 1 from appointment_status_history h
				              where h.appointment_id=a.id and h.to_status='CONFIRMED') as was_confirmed
				from appointment a join service_credit c on c.id=a.service_credit_id where a.id=:id
				""").param("id", conversationId).param("actorId", actorId).query((row, ignored) -> new AppointmentChat(
				row.getObject("id", UUID.class), row.getObject("user_account_id", UUID.class),
				row.getObject("specialist_account_id", UUID.class), row.getString("status"),
				AppointmentModality.valueOf(row.getString("modality")),
				row.getTimestamp("scheduled_start_at").toInstant(), row.getTimestamp("scheduled_end_at").toInstant(),
				row.getString("session_outcome"), row.getString("evidence_failure_reason"),
				row.getString("credit_state"), row.getBoolean("participant_checked_in"),
				row.getBoolean("was_rescheduled"), row.getBoolean("was_confirmed")))
				.optional().orElseThrow(this::notFound);
		if (!participantMatches(appointment, actorId, actorRole)) throw notFound();
		var now = clock.instant();
		var decision = classify(appointment, now);
		return response(appointment, decision, now);
	}

	private Decision classify(AppointmentChat appointment, Instant now) {
		if (appointment.modality() != AppointmentModality.IN_APP_CHAT || !appointment.wasConfirmed()) {
			return new Decision(AppointmentChatEligibility.Phase.NOT_AVAILABLE, "APPOINTMENT_NOT_CONFIRMED", false, false, false, false);
		}
		if (appointment.status().equals("CANCELLED")) {
			var rescheduled = appointment.wasRescheduled();
			return new Decision(rescheduled ? AppointmentChatEligibility.Phase.RESCHEDULED : AppointmentChatEligibility.Phase.CANCELLED,
					rescheduled ? "APPOINTMENT_RESCHEDULED" : "APPOINTMENT_CANCELLED", false, false, true, false);
		}
		if (appointment.sessionOutcome() != null) {
			return new Decision(AppointmentChatEligibility.Phase.valueOf(appointment.sessionOutcome()),
					"SESSION_" + appointment.sessionOutcome(), false, false, true, false);
		}
		if (appointment.status().equals("SESSION_ENDED") || !now.isBefore(appointment.scheduledEndAt())) {
			var review = appointment.evidenceFailureReason() != null;
			return new Decision(review ? AppointmentChatEligibility.Phase.EVIDENCE_REVIEW
					: AppointmentChatEligibility.Phase.ENDED_PROCESSING,
					review ? "SESSION_EVIDENCE_REVIEW" : "SESSION_OUTCOME_PROCESSING", false, false, true, false);
		}
		if (now.isBefore(appointment.scheduledStartAt().minus(WAITING_WINDOW))) {
			return new Decision(AppointmentChatEligibility.Phase.TOO_EARLY, "CHAT_ENTRY_TOO_EARLY", false, false, false, false);
		}
		if (now.isBefore(appointment.scheduledStartAt())) {
			return new Decision(AppointmentChatEligibility.Phase.WAITING, "APPOINTMENT_WAITING", true, false, true, true);
		}
		if (!appointment.status().equals("CONFIRMED") && !appointment.status().equals("IN_PROGRESS")) {
			return new Decision(AppointmentChatEligibility.Phase.NOT_AVAILABLE, "APPOINTMENT_NOT_CONFIRMED", false, false, true, false);
		}
		return new Decision(AppointmentChatEligibility.Phase.ACTIVE, "APPOINTMENT_ACTIVE", true, true, true, true);
	}

	private boolean participantMatches(AppointmentChat appointment, UUID actorId, String actorRole) {
		return actorRole.equals("USER") && appointment.userId().equals(actorId)
				|| actorRole.equals("SPECIALIST") && appointment.specialistId().equals(actorId);
	}

	private AppointmentChatEligibility response(AppointmentChat appointment, Decision decision, Instant now) {
		return new AppointmentChatEligibility(appointment.id(), appointment.id(), appointment.userId(),
				appointment.specialistId(), decision.phase(), decision.reasonCode(), decision.subscribeAllowed(),
				decision.sendAllowed(), decision.historyAllowed(), decision.checkInAllowed(), appointment.participantCheckedIn(),
				appointment.sessionOutcome(), appointment.creditState(), appointment.scheduledStartAt(),
				appointment.scheduledEndAt(), now);
	}

	private ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_CHAT_NOT_FOUND", "The appointment chat was not found");
	}

	private record AppointmentChat(UUID id, UUID userId, UUID specialistId, String status,
			AppointmentModality modality, Instant scheduledStartAt, Instant scheduledEndAt,
			String sessionOutcome, String evidenceFailureReason, String creditState,
			boolean participantCheckedIn, boolean wasRescheduled, boolean wasConfirmed) { }

	private record Decision(AppointmentChatEligibility.Phase phase, String reasonCode,
			boolean subscribeAllowed, boolean sendAllowed, boolean historyAllowed, boolean checkInAllowed) { }
}

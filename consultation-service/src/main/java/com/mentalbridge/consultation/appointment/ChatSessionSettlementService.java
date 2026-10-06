package com.mentalbridge.consultation.appointment;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.credits.CreditEventType;
import com.mentalbridge.consultation.credits.ServiceCreditService;
import com.mentalbridge.consultation.earnings.SpecialistEarningService;

@Service
public class ChatSessionSettlementService {

	private static final int BATCH_SIZE = 100;

	private final JdbcClient jdbc;
	private final ServiceCreditService credits;
	private final SpecialistEarningService earnings;
	private final ChatSessionCompletionPolicy policy;
	private final Clock clock;

	public ChatSessionSettlementService(JdbcClient jdbc, ServiceCreditService credits,
			SpecialistEarningService earnings, ChatSessionCompletionPolicy policy, Clock clock) {
		this.jdbc = jdbc;
		this.credits = credits;
		this.earnings = earnings;
		this.policy = policy;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<UUID> dueEndIds() {
		return jdbc.sql("""
				select id from appointment
				where modality='IN_APP_CHAT' and status in ('CONFIRMED', 'IN_PROGRESS')
				  and scheduled_end_at <= :now
				order by scheduled_end_at, id limit :limit
				""").param("now", databaseInstant(clock.instant())).param("limit", BATCH_SIZE).query(UUID.class).list();
	}

	@Transactional(readOnly = true)
	public List<UUID> dueSettlementIds() {
		return jdbc.sql("""
				select id from appointment
				where modality='IN_APP_CHAT' and status='SESSION_ENDED' and session_outcome is null
				  and scheduled_end_at + interval '5 minutes' <= :now
				order by scheduled_end_at, id limit :limit
				""").param("now", databaseInstant(clock.instant())).param("limit", BATCH_SIZE).query(UUID.class).list();
	}

	@Transactional
	public void end(UUID appointmentId) {
		var now = clock.instant();
		var appointment = findForUpdate(appointmentId);
		if (appointment == null || !List.of("CONFIRMED", "IN_PROGRESS").contains(appointment.status())
				|| now.isBefore(appointment.end())) return;
		jdbc.sql("""
				update appointment set status='SESSION_ENDED', session_policy_version=:policy,
				       session_ended_at=:now, updated_at=:now, version=version+1
				where id=:id and status in ('CONFIRMED', 'IN_PROGRESS')
				""").param("policy", ChatSessionCompletionPolicy.VERSION).param("now", databaseInstant(now))
				.param("id", appointmentId).update();
		jdbc.sql("""
				insert into appointment_status_history (
				    id, appointment_id, from_status, to_status, changed_by, reason, idempotency_key, changed_at
				) values (:eventId, :id, :fromStatus, 'SESSION_ENDED', null,
				          'SCHEDULED_WINDOW_ENDED', :key, :now)
				on conflict (appointment_id, idempotency_key) where idempotency_key is not null do nothing
				""").param("eventId", UUID.randomUUID()).param("id", appointmentId)
				.param("fromStatus", appointment.status()).param("key", "session-ended:" + appointmentId)
				.param("now", databaseInstant(now)).update();
	}

	@Transactional
	public void settle(UUID appointmentId) {
		var now = clock.instant();
		var appointment = findForUpdate(appointmentId);
		if (appointment == null || !appointment.status().equals("SESSION_ENDED")
				|| appointment.outcome() != null || now.isBefore(appointment.end().plus(ChatSessionCompletionPolicy.GRACE))) {
			return;
		}
		if (appointment.evidenceFailureReason() != null) {
			if (now.isBefore(appointment.end().plus(ChatSessionCompletionPolicy.RECONCILIATION))) {
				jdbc.sql("""
						update appointment set evidence_review_started_at=coalesce(evidence_review_started_at, :now),
						updated_at=:now, version=version+1 where id=:id and session_outcome is null
						""").param("now", databaseInstant(now)).param("id", appointmentId).update();
				return;
			}
			finalize(appointment, new ChatSessionCompletionPolicy.Decision(ChatSessionOutcome.EVIDENCE_REVIEW,
					"SYSTEM_EVIDENCE_UNAVAILABLE_TIMEOUT"), now);
			return;
		}
		var evidence = jdbc.sql("""
				select participant_role, evidence_type, interval_started_at, occurred_at
				from appointment_chat_evidence where appointment_id=:id and occurred_at < :end
				order by occurred_at, id
				""").param("id", appointmentId).param("end", databaseInstant(appointment.end()))
				.query((row, ignored) -> new ChatSessionCompletionPolicy.Evidence(row.getString("participant_role"),
						ChatEvidenceRequest.Type.valueOf(row.getString("evidence_type")),
						instant(row, "interval_started_at"), row.getTimestamp("occurred_at").toInstant())).list();
		finalize(appointment, policy.evaluate(appointment.start(), appointment.end(), evidence), now);
	}

	private void finalize(SettlementTarget appointment, ChatSessionCompletionPolicy.Decision decision, Instant now) {
		var creditEvent = switch (decision.outcome()) {
			case COMPLETED -> CreditEventType.CONSUMED;
			case USER_NO_SHOW -> CreditEventType.FORFEITED;
			case SPECIALIST_NO_SHOW, BOTH_NO_SHOW, INSUFFICIENT_EVIDENCE, EVIDENCE_REVIEW -> CreditEventType.RELEASED;
		};
		credits.transition(appointment.userId(), appointment.creditId(), appointment.id(), creditEvent,
				"session-settle:" + appointment.id());
		var completed = decision.outcome() == ChatSessionOutcome.COMPLETED;
		var completionFact = completed ? UUID.randomUUID() : null;
		jdbc.sql("""
				update appointment set status=:status, session_outcome=:outcome,
				       session_outcome_reason=:reason, session_settled_at=:now,
				       completion_fact_id=:completionFact, updated_at=:now, version=version+1
				where id=:id and status='SESSION_ENDED' and session_outcome is null
				""").param("status", completed ? "COMPLETED" : "SESSION_ENDED")
				.param("outcome", decision.outcome().name()).param("reason", decision.reason())
				.param("completionFact", completionFact).param("now", databaseInstant(now))
				.param("id", appointment.id()).update();
		if (completed) {
			jdbc.sql("""
					insert into appointment_status_history (
					    id, appointment_id, from_status, to_status, changed_by, reason, idempotency_key, changed_at
					) values (:eventId, :id, 'SESSION_ENDED', 'COMPLETED', null,
					          'EVIDENCE_REQUIREMENTS_MET', :key, :now)
					on conflict (appointment_id, idempotency_key) where idempotency_key is not null do nothing
					""").param("eventId", UUID.randomUUID()).param("id", appointment.id())
					.param("key", "session-completed:" + appointment.id()).param("now", databaseInstant(now)).update();
			earnings.createForCompletedAppointment(appointment.id());
		}
	}

	private SettlementTarget findForUpdate(UUID id) {
		return jdbc.sql("""
				select id, user_account_id, service_credit_id, status, scheduled_start_at, scheduled_end_at,
				       session_outcome, evidence_failure_reason
				from appointment where id=:id for update
				""").param("id", id).query((row, ignored) -> new SettlementTarget(row.getObject("id", UUID.class),
				row.getObject("user_account_id", UUID.class), row.getObject("service_credit_id", UUID.class),
				row.getString("status"), row.getTimestamp("scheduled_start_at").toInstant(),
				row.getTimestamp("scheduled_end_at").toInstant(), row.getString("session_outcome"),
				row.getString("evidence_failure_reason"))).optional().orElse(null);
	}

	private Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private OffsetDateTime databaseInstant(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private record SettlementTarget(UUID id, UUID userId, UUID creditId, String status,
			Instant start, Instant end, String outcome, String evidenceFailureReason) { }
}

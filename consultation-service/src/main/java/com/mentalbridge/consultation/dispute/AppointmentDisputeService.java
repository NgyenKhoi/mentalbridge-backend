package com.mentalbridge.consultation.dispute;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.credits.ServiceCreditService;
import com.mentalbridge.consultation.shared.ApiException;

@Service
public class AppointmentDisputeService {

	static final Duration OPEN_WINDOW = Duration.ofHours(24);
	private static final int ADMIN_LIMIT = 100;

	private final JdbcClient jdbc;
	private final ServiceCreditService credits;
	private final Clock clock;

	public AppointmentDisputeService(JdbcClient jdbc, ServiceCreditService credits, Clock clock) {
		this.jdbc = jdbc;
		this.credits = credits;
		this.clock = clock;
	}

	@Transactional
	public AppointmentDisputeResponse open(UUID actorId, String actorRole, UUID appointmentId,
			String idempotencyKey, OpenAppointmentDisputeRequest request) {
		var replay = findOpenCommand(actorId, idempotencyKey);
		if (replay != null) {
			if (!replay.appointmentId().equals(appointmentId)
					|| !replay.reasonCode().equals(request.reasonCode().name())
					|| !java.util.Objects.equals(replay.evidenceType(), name(request.evidenceType()))
					|| !sameInstant(replay.evidenceOccurredAt(), request.evidenceOccurredAt())) {
				throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used for another dispute command");
			}
			return replay;
		}
		if (!List.of("USER", "SPECIALIST").contains(actorRole)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "APPOINTMENT_DISPUTE_PARTICIPANT_REQUIRED",
					"Only an appointment participant can open a dispute");
		}
		var target = lockParticipantAppointment(actorId, actorRole, appointmentId);
		var now = clock.instant();
		if (target.sessionOutcome() == null || target.sessionSettledAt() == null
				|| !List.of("SESSION_ENDED", "COMPLETED").contains(target.status())) {
			throw conflict("APPOINTMENT_DISPUTE_NOT_ELIGIBLE", "The appointment has no eligible settled session outcome");
		}
		var eligibleUntil = target.sessionSettledAt().plus(OPEN_WINDOW);
		if (now.isAfter(eligibleUntil)) {
			throw conflict("APPOINTMENT_DISPUTE_WINDOW_EXPIRED", "The appointment dispute window has expired");
		}
		if (request.evidenceOccurredAt() != null
				&& (request.evidenceType() == null || request.evidenceOccurredAt().isAfter(now)
						|| request.evidenceOccurredAt().isBefore(target.start().minus(Duration.ofHours(1)))
						|| request.evidenceOccurredAt().isAfter(target.end().plus(Duration.ofHours(1))))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "APPOINTMENT_DISPUTE_EVIDENCE_INVALID",
					"Evidence metadata must identify a bounded appointment-time operational fact");
		}
		if (request.evidenceType() != null && request.evidenceOccurredAt() == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "APPOINTMENT_DISPUTE_EVIDENCE_INVALID",
					"Evidence type and occurrence time must be supplied together");
		}
		var id = UUID.randomUUID();
		var inserted = jdbc.sql("""
				insert into appointment_dispute (
				 id, appointment_id, appointment_version, opened_by_account_id, opened_by_role,
				 reason_code, evidence_type, evidence_occurred_at, opened_at, eligible_until,
				 status, open_idempotency_key, created_at, updated_at
				) values (
				 :id, :appointmentId, :appointmentVersion, :actorId, :actorRole,
				 :reason, :evidenceType, :evidenceAt, :now, :eligibleUntil,
				 'OPEN', :key, :now, :now
				) on conflict (appointment_id) do nothing
				""").param("id", id).param("appointmentId", appointmentId)
				.param("appointmentVersion", target.version()).param("actorId", actorId).param("actorRole", actorRole)
				.param("reason", request.reasonCode().name()).param("evidenceType", name(request.evidenceType()))
				.param("evidenceAt", database(request.evidenceOccurredAt())).param("now", database(now))
				.param("eligibleUntil", database(eligibleUntil)).param("key", idempotencyKey).update();
		if (inserted == 0) throw conflict("APPOINTMENT_DISPUTE_ALREADY_EXISTS", "The appointment already has a dispute");
		return findParticipant(actorId, actorRole, appointmentId);
	}

	@Transactional(readOnly = true)
	public AppointmentDisputeResponse participant(UUID actorId, String actorRole, UUID appointmentId) {
		return findParticipant(actorId, actorRole, appointmentId);
	}

	@Transactional(readOnly = true)
	public AppointmentDisputeResponse.ListResponse adminList(String status) {
		if (!List.of("OPEN", "RESOLVED").contains(status)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "APPOINTMENT_DISPUTE_STATUS_INVALID",
					"Dispute status must be OPEN or RESOLVED");
		}
		var items = jdbc.sql(BASE_SELECT + " where d.status=:status order by d.opened_at, d.id limit :limit")
				.param("status", status).param("limit", ADMIN_LIMIT).query(this::map).list();
		return new AppointmentDisputeResponse.ListResponse(items, items.size(), clock.instant());
	}

	@Transactional
	public AppointmentDisputeResponse resolve(UUID adminId, UUID disputeId, long expectedVersion,
			String idempotencyKey, ResolveAppointmentDisputeRequest request) {
		var dispute = lockDispute(disputeId);
		if (dispute.status().equals("RESOLVED")) {
			if (idempotencyKey.equals(dispute.resolutionKey())
					&& request.outcome().name().equals(dispute.resolutionOutcome())
					&& request.reasonCode().name().equals(dispute.resolutionReason())) return find(disputeId);
			throw conflict("APPOINTMENT_DISPUTE_ALREADY_RESOLVED", "The dispute already has an immutable resolution");
		}
		if (dispute.version() != expectedVersion) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "APPOINTMENT_DISPUTE_VERSION_MISMATCH",
					"The dispute version is stale");
		}
		validateResolution(request);
		var target = lockAppointment(dispute.appointmentId());
		var creditAction = "NONE";
		if (request.outcome() == ResolveAppointmentDisputeRequest.Outcome.RELEASE_USER_CREDIT) {
			creditAction = credits.adjustRelease(target.userId(), target.creditId(), target.id(),
					"dispute-adjust:" + dispute.id()) ? "ADJUSTED_RELEASED" : "ALREADY_AVAILABLE";
		}
		var now = clock.instant();
		jdbc.sql("""
				update appointment_dispute set status='RESOLVED', resolution_outcome=:outcome,
				 resolution_reason=:reason, resolved_by=:adminId, resolved_at=:now,
				 prior_appointment_status=:appointmentStatus, prior_session_outcome=:sessionOutcome,
				 resulting_appointment_status=:appointmentStatus, resulting_session_outcome=:sessionOutcome,
				 credit_action=:creditAction, resolution_idempotency_key=:key,
				 updated_at=:now, version=version+1 where id=:id and status='OPEN' and version=:version
				""").param("outcome", request.outcome().name()).param("reason", request.reasonCode().name())
				.param("adminId", adminId).param("now", database(now)).param("appointmentStatus", target.status())
				.param("sessionOutcome", target.sessionOutcome()).param("creditAction", creditAction)
				.param("key", idempotencyKey).param("id", disputeId).param("version", expectedVersion).update();
		return find(disputeId);
	}

	private void validateResolution(ResolveAppointmentDisputeRequest request) {
		var uphold = request.outcome() == ResolveAppointmentDisputeRequest.Outcome.UPHOLD_RECORDED_OUTCOME;
		var matching = request.reasonCode() == ResolveAppointmentDisputeRequest.ReasonCode.EVIDENCE_SUPPORTS_RECORDED_OUTCOME;
		if (uphold != matching) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "APPOINTMENT_DISPUTE_RESOLUTION_INVALID",
					"Resolution outcome and reason are inconsistent");
		}
	}

	private AppointmentDisputeResponse findParticipant(UUID actorId, String actorRole, UUID appointmentId) {
		return jdbc.sql(BASE_SELECT + """
				 where d.appointment_id=:appointmentId
				   and ((:actorRole='USER' and a.user_account_id=:actorId)
				     or (:actorRole='SPECIALIST' and a.specialist_account_id=:actorId))
				""").param("appointmentId", appointmentId).param("actorRole", actorRole).param("actorId", actorId)
				.query(this::map).optional().orElseThrow(() -> notFound());
	}

	private AppointmentDisputeResponse findOpenCommand(UUID actorId, String key) {
		return jdbc.sql(BASE_SELECT + " where d.opened_by_account_id=:actorId and d.open_idempotency_key=:key")
				.param("actorId", actorId).param("key", key).query(this::map).optional().orElse(null);
	}

	private AppointmentDisputeResponse find(UUID disputeId) {
		return jdbc.sql(BASE_SELECT + " where d.id=:id").param("id", disputeId).query(this::map).optional()
				.orElseThrow(() -> notFound());
	}

	private ParticipantAppointment lockParticipantAppointment(UUID actorId, String role, UUID appointmentId) {
		return jdbc.sql("""
				select id, status, scheduled_start_at, scheduled_end_at, session_outcome, session_settled_at, version
				from appointment where id=:id
				 and ((:role='USER' and user_account_id=:actorId)
				   or (:role='SPECIALIST' and specialist_account_id=:actorId)) for update
				""").param("id", appointmentId).param("role", role).param("actorId", actorId)
				.query((row, ignored) -> new ParticipantAppointment(row.getObject("id", UUID.class), row.getString("status"),
						row.getTimestamp("scheduled_start_at").toInstant(), row.getTimestamp("scheduled_end_at").toInstant(),
						row.getString("session_outcome"),
						instant(row, "session_settled_at"), row.getLong("version"))).optional().orElseThrow(() -> notFound());
	}

	private Dispute lockDispute(UUID disputeId) {
		return jdbc.sql("""
				select id, appointment_id, status, resolution_outcome, resolution_reason,
				 resolution_idempotency_key, version from appointment_dispute where id=:id for update
				""").param("id", disputeId).query((row, ignored) -> new Dispute(row.getObject("id", UUID.class),
				row.getObject("appointment_id", UUID.class), row.getString("status"), row.getString("resolution_outcome"),
				row.getString("resolution_reason"), row.getString("resolution_idempotency_key"), row.getLong("version")))
				.optional().orElseThrow(() -> notFound());
	}

	private SettlementAppointment lockAppointment(UUID appointmentId) {
		return jdbc.sql("""
				select id, user_account_id, service_credit_id, status, session_outcome
				from appointment where id=:id for update
				""").param("id", appointmentId).query((row, ignored) -> new SettlementAppointment(
				row.getObject("id", UUID.class), row.getObject("user_account_id", UUID.class),
				row.getObject("service_credit_id", UUID.class), row.getString("status"), row.getString("session_outcome")))
				.single();
	}

	private AppointmentDisputeResponse map(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
		return new AppointmentDisputeResponse(row.getObject("id", UUID.class), row.getObject("appointment_id", UUID.class),
				row.getLong("appointment_version"), row.getString("status"), row.getString("opened_by_role"),
				row.getString("reason_code"), row.getString("evidence_type"), instant(row, "evidence_occurred_at"),
				row.getTimestamp("opened_at").toInstant(), row.getTimestamp("eligible_until").toInstant(),
				row.getString("status").equals("OPEN")
						|| "RELEASE_USER_CREDIT".equals(row.getString("resolution_outcome")),
				row.getString("resolution_outcome"),
				row.getString("resolution_reason"), instant(row, "resolved_at"),
				row.getString("prior_appointment_status"), row.getString("prior_session_outcome"),
				row.getString("resulting_appointment_status"), row.getString("resulting_session_outcome"),
				row.getString("credit_action"), row.getLong("version"));
	}

	private Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private OffsetDateTime database(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(
				instant.truncatedTo(java.time.temporal.ChronoUnit.MICROS), ZoneOffset.UTC);
	}

	private String name(Enum<?> value) { return value == null ? null : value.name(); }

	private boolean sameInstant(Instant persisted, Instant requested) {
		return persisted == null ? requested == null
				: requested != null && persisted.equals(requested.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
	}

	private ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_DISPUTE_NOT_FOUND", "The dispute was not found");
	}

	private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }

	private static final String BASE_SELECT = """
			select d.id, d.appointment_id, d.appointment_version, d.status, d.opened_by_role,
			 d.reason_code, d.evidence_type, d.evidence_occurred_at, d.opened_at, d.eligible_until,
			 d.resolution_outcome, d.resolution_reason, d.resolved_at,
			 d.prior_appointment_status, d.prior_session_outcome,
			 d.resulting_appointment_status, d.resulting_session_outcome, d.credit_action, d.version
			from appointment_dispute d join appointment a on a.id=d.appointment_id
			""";

	private record ParticipantAppointment(UUID id, String status, Instant start, Instant end, String sessionOutcome,
			Instant sessionSettledAt, long version) { }
	private record Dispute(UUID id, UUID appointmentId, String status, String resolutionOutcome,
			String resolutionReason, String resolutionKey, long version) { }
	private record SettlementAppointment(UUID id, UUID userId, UUID creditId, String status, String sessionOutcome) { }
}

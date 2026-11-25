package com.mentalbridge.care.consultationbrief;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SpecialistClientContinuityService {

	private final AppointmentContextClient appointments;
	private final JdbcClient jdbc;
	private final TransactionTemplate transactions;
	private final Clock clock;

	public SpecialistClientContinuityService(AppointmentContextClient appointments, JdbcClient jdbc,
			TransactionTemplate transactions, Clock clock) {
		this.appointments = appointments;
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.clock = clock;
	}

	public ListView list(UUID specialistId, String bearerToken, UUID correlationId) {
		var relationships = appointments.clientRelationships(bearerToken, correlationId);
		return transactions.execute(status -> compose(specialistId, correlationId, relationships));
	}

	private ListView compose(UUID specialistId, UUID correlationId,
			SpecialistClientRelationshipProjection relationships) {
		var now = clock.instant();
		var items = relationships.items().stream().map(relationship -> {
			var name = jdbc.sql("select display_name from user_profile where account_id=:userId")
					.param("userId", relationship.userAccountId()).query(String.class).optional()
					.orElse("Người dùng MentalBridge");
			var access = access(relationship, specialistId, now);
			audit(relationship.appointmentId(), access.grantId(), specialistId, correlationId,
					"CONTINUITY_LIST_READ", now);
			return new Item(relationship.appointmentId(), relationship.userAccountId(), name,
					relationship.status(), relationship.modality(), relationship.scheduledStartAt(),
					relationship.scheduledEndAt(), relationship.appointmentVersion(), access.state(),
					access.snapshotVersion(), access.accessStartAt(), access.accessEndAt());
		}).toList();
		return new ListView(items, items.size(), relationships.generatedAt(), relationships.recentSince(),
				relationships.policyVersion());
	}

	private Access access(SpecialistClientRelationshipProjection.Item relationship, UUID specialistId,
			Instant now) {
		var value = jdbc.sql("""
				select g.id,g.status,g.access_start_at,g.access_end_at,s.snapshot_version,
				       b.status as brief_status,b.appointment_start_at,b.appointment_end_at,b.appointment_version,
				       s.deleted_at
				from consultation_brief b
				left join consultation_brief_grant g on g.brief_id=b.id
				left join consultation_brief_snapshot s on s.id=g.snapshot_id
				where b.appointment_id=:appointmentId and b.user_id=:userId and b.specialist_id=:specialistId
				order by g.approved_at desc nulls last,g.id desc limit 1
				""").param("appointmentId", relationship.appointmentId())
				.param("userId", relationship.userAccountId()).param("specialistId", specialistId)
				.query((rs, row) -> new AccessRow(rs.getObject("id", UUID.class), rs.getString("status"),
						rs.getTimestamp("access_start_at") == null ? null : rs.getTimestamp("access_start_at").toInstant(),
						rs.getTimestamp("access_end_at") == null ? null : rs.getTimestamp("access_end_at").toInstant(),
						rs.getObject("snapshot_version") == null ? null : rs.getLong("snapshot_version"),
						rs.getString("brief_status"), rs.getTimestamp("appointment_start_at").toInstant(),
						rs.getTimestamp("appointment_end_at").toInstant(), rs.getLong("appointment_version"),
						rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant()))
				.optional().orElse(null);
		if (value == null || value.grantId() == null) return Access.none("NOT_SHARED");
		if (!"ACTIVE".equals(value.grantStatus()) || "DELETED".equals(value.briefStatus())
				|| value.deletedAt() != null) return Access.from(value, "REVOKED");
		if (!relationship.scheduledStartAt().equals(value.appointmentStartAt())
				|| !relationship.scheduledEndAt().equals(value.appointmentEndAt())
				|| relationship.appointmentVersion() != value.appointmentVersion()) return Access.from(value, "STALE");
		if (!List.of("CONFIRMED", "IN_PROGRESS").contains(relationship.status())) {
			return Access.from(value, "UNAVAILABLE");
		}
		if (now.isBefore(value.accessStartAt())) return Access.from(value, "TOO_EARLY");
		if (now.isAfter(value.accessEndAt())) return Access.from(value, "EXPIRED");
		return Access.from(value, "AVAILABLE");
	}

	private void audit(UUID appointmentId, UUID grantId, UUID specialistId, UUID correlationId,
			String reason, Instant now) {
		jdbc.sql("""
				insert into consultation_brief_audit (
				 id,appointment_id,grant_id,actor_id,actor_type,action,outcome,reason_code,correlation_id,occurred_at
				) values (:id,:appointmentId,:grantId,:actorId,'SPECIALIST','READ','ALLOWED',:reason,:correlationId,:now)
				""").param("id", UUID.randomUUID()).param("appointmentId", appointmentId)
				.param("grantId", grantId).param("actorId", specialistId).param("reason", reason)
				.param("correlationId", correlationId).param("now", Timestamp.from(now)).update();
	}

	public record Item(UUID appointmentId, UUID userAccountId, String userDisplayName, String status,
			String modality, Instant scheduledStartAt, Instant scheduledEndAt, long appointmentVersion,
			String briefAccessState, Long briefSnapshotVersion, Instant briefAccessStartAt,
			Instant briefAccessEndAt) { }
	public record ListView(List<Item> items, int count, Instant generatedAt, Instant recentSince,
			String policyVersion) { }
	private record AccessRow(UUID grantId, String grantStatus, Instant accessStartAt, Instant accessEndAt,
			Long snapshotVersion, String briefStatus, Instant appointmentStartAt, Instant appointmentEndAt,
			long appointmentVersion, Instant deletedAt) { }
	private record Access(UUID grantId, String state, Long snapshotVersion, Instant accessStartAt,
			Instant accessEndAt) {
		static Access none(String state) { return new Access(null, state, null, null, null); }
		static Access from(AccessRow row, String state) {
			return new Access(row.grantId(), state, row.snapshotVersion(), row.accessStartAt(), row.accessEndAt());
		}
	}
}

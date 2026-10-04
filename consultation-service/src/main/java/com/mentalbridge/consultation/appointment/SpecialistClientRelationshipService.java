package com.mentalbridge.consultation.appointment;

import java.sql.Timestamp;
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
public class SpecialistClientRelationshipService {

	private static final Duration RECENT_WINDOW = Duration.ofDays(90);
	private static final int LIST_LIMIT = 200;
	private static final String POLICY_VERSION = "specialist-client-continuity-v1";

	private final JdbcClient jdbc;
	private final Clock clock;

	public SpecialistClientRelationshipService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional(noRollbackFor = ApiException.class)
	public SpecialistClientRelationshipProjection list(UUID specialistId) {
		var now = clock.instant();
		var recentSince = now.minus(RECENT_WINDOW);
		var approved = jdbc.sql("select approval_status from specialist_profile where account_id=:specialistId")
				.param("specialistId", specialistId).query(String.class).optional();
		if (approved.isEmpty() || !"APPROVED".equals(approved.get())) {
			audit(specialistId, "DENIED", "SPECIALIST_NOT_APPROVED", 0, now);
			throw new ApiException(HttpStatus.FORBIDDEN, "SPECIALIST_CONTINUITY_ACCESS_DENIED",
					"Specialist continuity access is unavailable");
		}

		var items = jdbc.sql("""
				select id, user_account_id, status, modality, scheduled_start_at,
				       scheduled_end_at, version
				from appointment
				where specialist_account_id=:specialistId
				  and status in ('CONFIRMED','IN_PROGRESS','SESSION_ENDED','COMPLETED')
				  and (scheduled_start_at>=:now or scheduled_end_at>=:recentSince)
				order by case when scheduled_start_at>=:now then 0 else 1 end,
				         case when scheduled_start_at>=:now then scheduled_start_at end asc,
				         scheduled_start_at desc, id
				limit :limit
				""").param("specialistId", specialistId).param("now", Timestamp.from(now))
				.param("recentSince", Timestamp.from(recentSince)).param("limit", LIST_LIMIT)
				.query((rs, row) -> new SpecialistClientRelationshipProjection.Item(
						rs.getObject("id", UUID.class), rs.getObject("user_account_id", UUID.class),
						rs.getString("status"), AppointmentModality.valueOf(rs.getString("modality")),
						rs.getTimestamp("scheduled_start_at").toInstant(),
						rs.getTimestamp("scheduled_end_at").toInstant(), rs.getLong("version")))
				.list();
		audit(specialistId, "ALLOWED", "BOUNDED_RELATIONSHIPS_READ", items.size(), now);
		return new SpecialistClientRelationshipProjection(items, items.size(), now, recentSince, POLICY_VERSION);
	}

	private void audit(UUID specialistId, String outcome, String reason, int count, Instant now) {
		jdbc.sql("""
				insert into specialist_client_continuity_audit (
				 id, specialist_account_id, action, outcome, reason_code, relationship_count, occurred_at
				) values (:id,:specialistId,'LIST',:outcome,:reason,:count,:now)
				""").param("id", UUID.randomUUID()).param("specialistId", specialistId)
				.param("outcome", outcome).param("reason", reason).param("count", count)
				.param("now", Timestamp.from(now)).update();
	}
}

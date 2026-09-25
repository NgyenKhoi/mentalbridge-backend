package com.mentalbridge.care.reassessment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class ReassessmentSummaryStore {

	private final JdbcClient jdbc;

	ReassessmentSummaryStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<StoredSummary> findByRequest(UUID userId, String idempotencyKey) {
		return jdbc.sql("""
				select id, user_id, idempotency_key, request_hash, snapshot::text as snapshot, composed_at
				from reassessment_summary
				where user_id = :userId and idempotency_key = :idempotencyKey
				""")
				.param("userId", userId)
				.param("idempotencyKey", idempotencyKey)
				.query(this::stored)
				.optional();
	}

	Optional<StoredSummary> find(UUID userId, UUID summaryId) {
		return jdbc.sql("""
				select id, user_id, idempotency_key, request_hash, snapshot::text as snapshot, composed_at
				from reassessment_summary
				where user_id = :userId and id = :summaryId
				""")
				.param("userId", userId)
				.param("summaryId", summaryId)
				.query(this::stored)
				.optional();
	}

	Optional<StoredSummary> current(UUID userId) {
		return jdbc.sql("""
				select id, user_id, idempotency_key, request_hash, snapshot::text as snapshot, composed_at
				from reassessment_summary
				where user_id = :userId
				order by composed_at desc, id desc
				limit 1
				""")
				.param("userId", userId)
				.query(this::stored)
				.optional();
	}

	List<StoredSummary> history(UUID userId, Instant beforeTime, UUID beforeId, int limit) {
		if (beforeTime == null) {
			return jdbc.sql("""
					select id, user_id, idempotency_key, request_hash, snapshot::text as snapshot, composed_at
					from reassessment_summary
					where user_id = :userId
					order by composed_at desc, id desc
					limit :limit
					""")
					.param("userId", userId)
					.param("limit", limit)
					.query(this::stored)
					.list();
		}
		return jdbc.sql("""
				select id, user_id, idempotency_key, request_hash, snapshot::text as snapshot, composed_at
				from reassessment_summary
				where user_id = :userId and (composed_at, id) < (:beforeTime, :beforeId)
				order by composed_at desc, id desc
				limit :limit
				""")
				.param("userId", userId)
				.param("beforeTime", OffsetDateTime.ofInstant(beforeTime, ZoneOffset.UTC))
				.param("beforeId", beforeId)
				.param("limit", limit)
				.query(this::stored)
				.list();
	}

	@Transactional
	StoredSummary persist(UUID summaryId, UUID userId, String idempotencyKey, String requestHash, String summaryVersion,
			UUID journalJobId, UUID journalAnalysisId, ReassessmentSummaryView.Period previousPeriod,
			ReassessmentSummaryView.Period currentPeriod, String snapshot, Instant composedAt) {
		int inserted = jdbc.sql("""
				insert into reassessment_summary
					(id,user_id,idempotency_key,request_hash,summary_version,journal_job_id,journal_analysis_id,
					 previous_period_start,previous_period_end,current_period_start,current_period_end,
					 snapshot,composed_at)
				values
					(:id,:userId,:idempotencyKey,:requestHash,:summaryVersion,:journalJobId,:journalAnalysisId,
					 :previousStart,:previousEnd,:currentStart,:currentEnd,cast(:snapshot as jsonb),:composedAt)
				on conflict (user_id,idempotency_key) do nothing
				""")
				.param("id", summaryId)
				.param("userId", userId)
				.param("idempotencyKey", idempotencyKey)
				.param("requestHash", requestHash)
				.param("summaryVersion", summaryVersion)
				.param("journalJobId", journalJobId)
				.param("journalAnalysisId", journalAnalysisId)
				.param("previousStart", OffsetDateTime.ofInstant(previousPeriod.startAt(), ZoneOffset.UTC))
				.param("previousEnd", OffsetDateTime.ofInstant(previousPeriod.endAt(), ZoneOffset.UTC))
				.param("currentStart", OffsetDateTime.ofInstant(currentPeriod.startAt(), ZoneOffset.UTC))
				.param("currentEnd", OffsetDateTime.ofInstant(currentPeriod.endAt(), ZoneOffset.UTC))
				.param("snapshot", snapshot)
				.param("composedAt", OffsetDateTime.ofInstant(composedAt, ZoneOffset.UTC))
				.update();
		if (inserted == 1) {
			return new StoredSummary(summaryId, userId, idempotencyKey, requestHash, snapshot, composedAt);
		}
		return findByRequest(userId, idempotencyKey).orElseThrow(
				() -> new IllegalStateException("Idempotent reassessment summary was not readable"));
	}

	private StoredSummary stored(ResultSet row, int rowNumber) throws SQLException {
		return new StoredSummary(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
				row.getString("idempotency_key"), row.getString("request_hash"), row.getString("snapshot"),
				row.getTimestamp("composed_at").toInstant());
	}

	record StoredSummary(UUID id, UUID userId, String idempotencyKey, String requestHash, String snapshot,
			Instant composedAt) { }
}

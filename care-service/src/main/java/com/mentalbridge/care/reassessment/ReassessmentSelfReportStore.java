package com.mentalbridge.care.reassessment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class ReassessmentSelfReportStore {

	private final JdbcClient jdbc;

	ReassessmentSelfReportStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<StoredSelfReport> findByRequest(UUID userId, String idempotencyKey) {
		return jdbc.sql("""
				select * from reassessment_self_report
				where user_id=:userId and idempotency_key=:idempotencyKey
				""").param("userId", userId).param("idempotencyKey", idempotencyKey)
				.query(this::stored).optional();
	}

	Optional<StoredSelfReport> find(UUID userId, UUID selfReportId) {
		return jdbc.sql("""
				select * from reassessment_self_report
				where user_id=:userId and id=:selfReportId
				""").param("userId", userId).param("selfReportId", selfReportId)
				.query(this::stored).optional();
	}

	Optional<StoredSelfReport> current(UUID userId) {
		return jdbc.sql("""
				select * from reassessment_self_report
				where user_id=:userId and deleted_at is null
				order by current_period_end desc, current_period_start desc, updated_at desc, id desc
				limit 1
				""").param("userId", userId).query(this::stored).optional();
	}

	@Transactional
	StoredSelfReport persist(UUID id, UUID userId, String idempotencyKey, String requestHash,
			ReassessmentSummaryView.Period period, String currentExperience, String helpfulContext,
			String difficultContext, Instant now) {
		int inserted = jdbc.sql("""
				insert into reassessment_self_report
					(id,user_id,idempotency_key,request_hash,source_version,current_period_start,
					 current_period_end,current_experience,helpful_context,difficult_context,version,
					 authored_at,updated_at)
				values (:id,:userId,:key,:hash,'reassessment-self-report-v1',:startAt,:endAt,
					:experience,:helpful,:difficult,0,:now,:now)
				on conflict (user_id,idempotency_key) do nothing
				""").param("id", id).param("userId", userId).param("key", idempotencyKey)
				.param("hash", requestHash).param("startAt", at(period.startAt())).param("endAt", at(period.endAt()))
				.param("experience", currentExperience).param("helpful", helpfulContext)
				.param("difficult", difficultContext).param("now", at(now)).update();
		if (inserted == 1) return find(userId, id).orElseThrow();
		return findByRequest(userId, idempotencyKey).orElseThrow();
	}

	@Transactional
	Optional<StoredSelfReport> replace(UUID userId, UUID id, long expectedVersion, String currentExperience,
			String helpfulContext, String difficultContext, Instant now) {
		int updated = jdbc.sql("""
				update reassessment_self_report
				set current_experience=:experience, helpful_context=:helpful,
					difficult_context=:difficult, version=version+1, updated_at=:now
				where user_id=:userId and id=:id and version=:version and deleted_at is null
				""").param("experience", currentExperience).param("helpful", helpfulContext)
				.param("difficult", difficultContext).param("now", at(now)).param("userId", userId)
				.param("id", id).param("version", expectedVersion).update();
		return updated == 1 ? find(userId, id) : Optional.empty();
	}

	@Transactional
	boolean delete(UUID userId, UUID id, long expectedVersion, Instant now) {
		return jdbc.sql("""
				update reassessment_self_report
				set current_experience=null, helpful_context=null, difficult_context=null,
					version=version+1, updated_at=:now, deleted_at=:now
				where user_id=:userId and id=:id and version=:version and deleted_at is null
				""").param("now", at(now)).param("userId", userId).param("id", id)
				.param("version", expectedVersion).update() == 1;
	}

	private StoredSelfReport stored(ResultSet row, int rowNumber) throws SQLException {
		return new StoredSelfReport(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
				row.getString("idempotency_key"), row.getString("request_hash"), row.getString("source_version"),
				row.getTimestamp("current_period_start").toInstant(), row.getTimestamp("current_period_end").toInstant(),
				row.getString("current_experience"), row.getString("helpful_context"),
				row.getString("difficult_context"), row.getLong("version"), row.getTimestamp("authored_at").toInstant(),
				row.getTimestamp("updated_at").toInstant(), nullableInstant(row, "deleted_at"));
	}

	private OffsetDateTime at(Instant value) {
		return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
	}

	private Instant nullableInstant(ResultSet row, String column) throws SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	record StoredSelfReport(UUID id, UUID userId, String idempotencyKey, String requestHash, String sourceVersion,
			Instant periodStart, Instant periodEnd, String currentExperience, String helpfulContext,
			String difficultContext, long version, Instant authoredAt, Instant updatedAt, Instant deletedAt) {
		boolean deleted() {
			return deletedAt != null;
		}
	}
}

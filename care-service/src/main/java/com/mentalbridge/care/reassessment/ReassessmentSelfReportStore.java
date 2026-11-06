package com.mentalbridge.care.reassessment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.reassessment.ReassessmentSymptomCatalog.Checklist;

@Repository
class ReassessmentSelfReportStore {

	private final JdbcClient jdbc;
	private final UserProfileRepository profiles;

	ReassessmentSelfReportStore(JdbcClient jdbc, UserProfileRepository profiles) {
		this.jdbc = jdbc;
		this.profiles = profiles;
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

	Optional<StoredSelfReport> currentForPeriod(UUID userId, ReassessmentSummaryView.Period period) {
		return jdbc.sql("""
				select * from reassessment_self_report
				where user_id=:userId and current_period_start=:startAt and current_period_end=:endAt
				order by updated_at desc, id desc
				limit 1
				""").param("userId", userId).param("startAt", at(period.startAt()))
				.param("endAt", at(period.endAt())).query(this::stored).optional();
	}

	@Transactional
	StoredSelfReport persist(UUID id, UUID userId, String idempotencyKey, String requestHash,
			ReassessmentSummaryView.Period period, String currentExperience, String helpfulContext,
			String difficultContext, Instant now) {
		return persist(id, userId, idempotencyKey, requestHash, period, currentExperience, helpfulContext, difficultContext,
				null, now);
	}

	@Transactional
	StoredSelfReport persist(UUID id, UUID userId, String idempotencyKey, String requestHash,
			ReassessmentSummaryView.Period period, String currentExperience, String helpfulContext,
			String difficultContext, Checklist checklist, Instant now) {
		lockOwner(userId);
		int inserted = jdbc.sql("""
				insert into reassessment_self_report
					(id,user_id,idempotency_key,request_hash,source_version,current_period_start,
					 current_period_end,current_experience,helpful_context,difficult_context,version,
					 authored_at,updated_at,symptom_catalog_revision,symptom_codes)
				values (:id,:userId,:key,:hash,'reassessment-self-report-v1',:startAt,:endAt,
					:experience,:helpful,:difficult,0,:now,:now,:catalogRevision,:codes)
				on conflict (user_id,idempotency_key) do nothing
				""").param("id", id).param("userId", userId).param("key", idempotencyKey)
				.param("hash", requestHash).param("startAt", at(period.startAt())).param("endAt", at(period.endAt()))
				.param("experience", currentExperience).param("helpful", helpfulContext)
				.param("difficult", difficultContext).param("now", at(now))
				.param("catalogRevision", checklist == null ? null : checklist.catalogRevision())
				.param("codes", codes(checklist), java.sql.Types.ARRAY).update();
		if (inserted == 1) return find(userId, id).orElseThrow();
		return findByRequest(userId, idempotencyKey).orElseThrow();
	}

	@Transactional
	Optional<StoredSelfReport> replace(UUID userId, UUID id, long expectedVersion, String currentExperience,
			String helpfulContext, String difficultContext, Instant now) {
		return replace(userId, id, expectedVersion, currentExperience, helpfulContext, difficultContext, null, now);
	}

	@Transactional
	Optional<StoredSelfReport> replace(UUID userId, UUID id, long expectedVersion, String currentExperience,
			String helpfulContext, String difficultContext, Checklist checklist, Instant now) {
		lockOwner(userId);
		int updated = jdbc.sql("""
				update reassessment_self_report
				set current_experience=:experience, helpful_context=:helpful,
					difficult_context=:difficult, version=version+1, updated_at=:now,
					symptom_catalog_revision=case when :replaceChecklist then :catalogRevision else symptom_catalog_revision end,
					symptom_codes=case when :replaceChecklist then :codes else symptom_codes end
				where user_id=:userId and id=:id and version=:version and deleted_at is null
				""").param("experience", currentExperience).param("helpful", helpfulContext)
				.param("difficult", difficultContext).param("now", at(now)).param("userId", userId)
				.param("id", id).param("version", expectedVersion).param("replaceChecklist", checklist != null)
				.param("catalogRevision", checklist == null ? null : checklist.catalogRevision())
				.param("codes", codes(checklist), java.sql.Types.ARRAY).update();
		return updated == 1 ? find(userId, id) : Optional.empty();
	}

	@Transactional
	boolean delete(UUID userId, UUID id, long expectedVersion, Instant now) {
		lockOwner(userId);
		return jdbc.sql("""
				update reassessment_self_report
				set current_experience=null, helpful_context=null, difficult_context=null,
					symptom_catalog_revision=null, symptom_codes='{}',
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
				row.getTimestamp("updated_at").toInstant(), nullableInstant(row, "deleted_at"),
				ReassessmentSymptomCatalog.stored(row.getString("symptom_catalog_revision"),
						List.of((String[]) row.getArray("symptom_codes").getArray())));
	}

	private String[] codes(Checklist checklist) {
		return checklist == null ? new String[0] : checklist.selectedCodes().toArray(String[]::new);
	}

	private OffsetDateTime at(Instant value) {
		return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
	}

	private void lockOwner(UUID userId) {
		profiles.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"PROFILE_NOT_FOUND", "Care profile was not found"));
	}

	private Instant nullableInstant(ResultSet row, String column) throws SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	record StoredSelfReport(UUID id, UUID userId, String idempotencyKey, String requestHash, String sourceVersion,
			Instant periodStart, Instant periodEnd, String currentExperience, String helpfulContext,
			String difficultContext, long version, Instant authoredAt, Instant updatedAt, Instant deletedAt,
			Checklist symptomChecklist) {
		StoredSelfReport(UUID id, UUID userId, String idempotencyKey, String requestHash, String sourceVersion,
				Instant periodStart, Instant periodEnd, String currentExperience, String helpfulContext,
				String difficultContext, long version, Instant authoredAt, Instant updatedAt, Instant deletedAt) {
			this(id, userId, idempotencyKey, requestHash, sourceVersion, periodStart, periodEnd, currentExperience,
					helpfulContext, difficultContext, version, authoredAt, updatedAt, deletedAt, null);
		}

		boolean deleted() {
			return deletedAt != null;
		}
	}
}

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

import com.mentalbridge.care.assessment.ScreeningLevel;

@Repository
class ReassessmentEvidenceRepository {

	private final JdbcClient jdbc;

	ReassessmentEvidenceRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<AssessmentEvidence> findAssessment(UUID userId, UUID assessmentId) {
		return jdbc.sql("""
				select submission.id, submission.submitted_at, submission.voided_at,
				       definition.instrument, definition.version as questionnaire_version,
				       result.total_score, result.screening_level, result.scoring_version
				from assessment_submission submission
				join questionnaire_definition definition on definition.id = submission.definition_id
				left join assessment_result result on result.submission_id = submission.id
				where submission.id = :assessmentId and submission.user_id = :userId
				""")
				.param("assessmentId", assessmentId)
				.param("userId", userId)
				.query(this::assessment)
				.optional();
	}

	Optional<AssessmentEvidence> findPrevious(UUID userId, AssessmentEvidence current) {
		return jdbc.sql("""
				select submission.id, submission.submitted_at, submission.voided_at,
				       definition.instrument, definition.version as questionnaire_version,
				       result.total_score, result.screening_level, result.scoring_version
				from assessment_submission submission
				join questionnaire_definition definition on definition.id = submission.definition_id
				join assessment_result result on result.submission_id = submission.id
				where submission.user_id = :userId
				  and submission.voided_at is null
				  and definition.instrument = :instrument
				  and result.scoring_version = :scoringVersion
				  and (submission.submitted_at < :submittedAt
				       or (submission.submitted_at = :submittedAt and submission.id < :assessmentId))
				order by submission.submitted_at desc, submission.id desc
				limit 1
				""")
				.param("userId", userId)
				.param("instrument", current.instrument())
				.param("scoringVersion", current.scoringVersion())
				.param("submittedAt", OffsetDateTime.ofInstant(current.submittedAt(), ZoneOffset.UTC))
				.param("assessmentId", current.assessmentId())
				.query(this::assessment)
				.optional();
	}

	List<EngagementEvidence> findReusableEngagement(UUID userId, Instant startAt, Instant endAt) {
		return jdbc.sql("""
				select id, support_plan_id, source_plan_version, source_slot_key, source_resource_id,
				       source_content_version, scheduled_at, state, helpfulness, barrier_code,
				       reflection, engagement_updated_at
				from support_plan_activity_occurrence
				where user_id = :userId
				  and summary_reuse_approved = true
				  and state in ('COMPLETED','SKIPPED')
				  and scheduled_at >= :startAt and scheduled_at < :endAt
				order by scheduled_at, id
				""")
				.param("userId", userId)
				.param("startAt", OffsetDateTime.ofInstant(startAt, ZoneOffset.UTC))
				.param("endAt", OffsetDateTime.ofInstant(endAt, ZoneOffset.UTC))
				.query(this::engagement)
				.list();
	}

	private AssessmentEvidence assessment(ResultSet row, int rowNumber) throws SQLException {
		var screeningLevel = row.getString("screening_level");
		return new AssessmentEvidence(row.getObject("id", UUID.class), row.getTimestamp("submitted_at").toInstant(),
				nullableInstant(row, "voided_at"), row.getString("instrument"),
				row.getString("questionnaire_version"), nullableInteger(row, "total_score"),
				screeningLevel == null ? null : ScreeningLevel.valueOf(screeningLevel), row.getString("scoring_version"));
	}

	private EngagementEvidence engagement(ResultSet row, int rowNumber) throws SQLException {
		return new EngagementEvidence(row.getObject("id", UUID.class), row.getObject("support_plan_id", UUID.class),
				row.getLong("source_plan_version"), row.getString("source_slot_key"),
				row.getObject("source_resource_id", UUID.class), row.getLong("source_content_version"),
				row.getTimestamp("scheduled_at").toInstant(), row.getString("state"), row.getString("helpfulness"),
				row.getString("barrier_code"), row.getString("reflection"),
				row.getTimestamp("engagement_updated_at").toInstant());
	}

	private Integer nullableInteger(ResultSet row, String column) throws SQLException {
		var value = row.getInt(column);
		return row.wasNull() ? null : value;
	}

	private Instant nullableInstant(ResultSet row, String column) throws SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	record AssessmentEvidence(UUID assessmentId, Instant submittedAt, Instant voidedAt, String instrument,
			String questionnaireVersion, Integer totalScore, ScreeningLevel screeningLevel, String scoringVersion) {

		boolean usable() {
			return voidedAt == null && totalScore != null && screeningLevel != null && scoringVersion != null;
		}
	}

	record EngagementEvidence(UUID occurrenceId, UUID supportPlanId, long sourcePlanVersion, String sourceSlotId,
			UUID sourceResourceId, long sourceContentVersion, Instant scheduledAt, String state, String helpfulness,
			String barrierCode, String reflection, Instant engagementUpdatedAt) { }
}

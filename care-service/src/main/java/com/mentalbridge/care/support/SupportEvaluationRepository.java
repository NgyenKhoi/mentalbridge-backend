package com.mentalbridge.care.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;

@Repository
class SupportEvaluationRepository {

	private final JdbcClient jdbc;

	SupportEvaluationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	boolean lockProfile(UUID userId) {
		return jdbc.sql("select account_id from user_profile where account_id = :userId for update")
				.param("userId", userId).query(UUID.class).optional().isPresent();
	}

	Optional<Evidence> findEvidence(UUID userId, UUID assessmentId) {
		return jdbc.sql("""
				select s.id, s.definition_id, s.voided_at, d.instrument,
				       d.version as questionnaire_version, r.screening_level,
				       r.scoring_version, r.safety_status, r.safety_policy_version
				from assessment_submission s
				join questionnaire_definition d on d.id = s.definition_id
				left join assessment_result r on r.submission_id = s.id
				where s.id = :assessmentId and s.user_id = :userId
				""").param("assessmentId", assessmentId).param("userId", userId)
				.query(this::evidence).optional();
	}

	Optional<String> currentPolicyVersion() {
		return jdbc.sql("""
				select version from support_policy_definition
				where locale = 'vi-VN' and status = 'PUBLISHED'
				""").query(String.class).optional();
	}

	boolean isEligible(String policyVersion, Evidence evidence) {
		return jdbc.sql("""
				select count(*) from support_policy_eligible_definition
				where policy_version = :policyVersion and definition_id = :definitionId
				  and instrument = :instrument and questionnaire_version = :questionnaireVersion
				  and scoring_version = :scoringVersion
				""").param("policyVersion", policyVersion).param("definitionId", evidence.definitionId())
				.param("instrument", evidence.instrument()).param("questionnaireVersion", evidence.questionnaireVersion())
				.param("scoringVersion", evidence.scoringVersion()).query(Long.class).single() == 1L;
	}

	Optional<Evaluation> findByIdempotencyKey(UUID userId, String key) {
		return jdbc.sql("select * from support_evaluation where user_id=:userId and idempotency_key=:key")
				.param("userId", userId).param("key", key).query(this::evaluation).optional();
	}

	Optional<Evaluation> findByEvidence(UUID userId, UUID phq9Id, UUID gad7Id, String policyVersion) {
		return jdbc.sql("""
				select * from support_evaluation where user_id=:userId and phq9_assessment_id=:phq9Id
				and gad7_assessment_id=:gad7Id and policy_version=:policyVersion
				""").param("userId", userId).param("phq9Id", phq9Id).param("gad7Id", gad7Id)
				.param("policyVersion", policyVersion).query(this::evaluation).optional();
	}

	Optional<Evaluation> findById(UUID userId, UUID evaluationId) {
		return jdbc.sql("select * from support_evaluation where user_id=:userId and id=:id")
				.param("userId", userId).param("id", evaluationId).query(this::evaluation).optional();
	}

	void insert(Evaluation evaluation) {
		jdbc.sql("""
				insert into support_evaluation
				(id,user_id,phq9_assessment_id,gad7_assessment_id,policy_version,support_tier,
				 primary_reason_code,secondary_reason_code,idempotency_key,request_hash,evaluated_at)
				values (:id,:userId,:phq9Id,:gad7Id,:policyVersion,:tier,:primaryReason,
				 :secondaryReason,:key,:requestHash,:evaluatedAt)
				""").param("id", evaluation.id()).param("userId", evaluation.userId())
				.param("phq9Id", evaluation.phq9AssessmentId()).param("gad7Id", evaluation.gad7AssessmentId())
				.param("policyVersion", evaluation.policyVersion()).param("tier", evaluation.tier().name())
				.param("primaryReason", evaluation.primaryReason().name())
				.param("secondaryReason", evaluation.secondaryReason() == null ? null : evaluation.secondaryReason().name())
				.param("key", evaluation.idempotencyKey()).param("requestHash", evaluation.requestHash())
				.param("evaluatedAt", OffsetDateTime.ofInstant(evaluation.evaluatedAt(), ZoneOffset.UTC)).update();
	}

	Meaning meaning(String policyVersion, String instrument, ScreeningLevel level) {
		return jdbc.sql("""
				select meaning_code,content_version,reference_period_days,meaning_text,limitation_text
				from screening_band_meaning where policy_version=:policyVersion
				and instrument=:instrument and screening_level=:level
				""").param("policyVersion", policyVersion).param("instrument", instrument).param("level", level.name())
				.query((row, number) -> new Meaning(row.getString("meaning_code"), row.getString("content_version"),
						row.getInt("reference_period_days"), row.getString("meaning_text"), row.getString("limitation_text")))
				.single();
	}

	Optional<Guidance> guidance(String policyVersion, SupportTier tier) {
		return jdbc.sql("""
				select next_step_code,content_version,next_step_text,boundary_text,safety_guidance_text
				from support_tier_guidance where policy_version=:policyVersion and support_tier=:tier
				""").param("policyVersion", policyVersion).param("tier", tier.name())
				.query((row, number) -> new Guidance(row.getString("next_step_code"), row.getString("content_version"),
						row.getString("next_step_text"), row.getString("boundary_text"), row.getString("safety_guidance_text")))
				.optional();
	}

	void appendOutbox(Evaluation evaluation, UUID correlationId, String payload) {
		jdbc.sql("""
				insert into outbox_event (id,message_type,schema_version,aggregate_type,aggregate_id,
				aggregate_version,correlation_id,payload,occurred_at,attempt_count,created_at)
				values (:id,'care.support-tier.resolved','1.0','SUPPORT_EVALUATION',:aggregateId,
				0,:correlationId,cast(:payload as jsonb),:occurredAt,0,:occurredAt)
				""").param("id", UUID.randomUUID()).param("aggregateId", evaluation.id())
				.param("correlationId", correlationId).param("payload", payload)
				.param("occurredAt", OffsetDateTime.ofInstant(evaluation.evaluatedAt(), ZoneOffset.UTC)).update();
	}

	private Evidence evidence(ResultSet row, int rowNumber) throws SQLException {
		var level = row.getString("screening_level");
		var safety = row.getString("safety_status");
		return new Evidence(row.getObject("id", UUID.class), row.getObject("definition_id", UUID.class),
				row.getTimestamp("voided_at") == null ? null : row.getTimestamp("voided_at").toInstant(),
				row.getString("instrument"), row.getString("questionnaire_version"),
				level == null ? null : ScreeningLevel.valueOf(level), row.getString("scoring_version"),
				safety == null ? null : SafetyStatus.valueOf(safety), row.getString("safety_policy_version"));
	}

	private Evaluation evaluation(ResultSet row, int rowNumber) throws SQLException {
		var secondary = row.getString("secondary_reason_code");
		return new Evaluation(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
				row.getObject("phq9_assessment_id", UUID.class), row.getObject("gad7_assessment_id", UUID.class),
				row.getString("policy_version"), SupportTier.valueOf(row.getString("support_tier")),
				SupportReasonCode.valueOf(row.getString("primary_reason_code")),
				secondary == null ? null : SupportReasonCode.valueOf(secondary), row.getString("idempotency_key"),
				row.getString("request_hash"), row.getTimestamp("evaluated_at").toInstant());
	}

	record Evidence(UUID assessmentId, UUID definitionId, Instant voidedAt, String instrument,
			String questionnaireVersion, ScreeningLevel screeningLevel, String scoringVersion,
			SafetyStatus safetyStatus, String safetyPolicyVersion) { }

	record Evaluation(UUID id, UUID userId, UUID phq9AssessmentId, UUID gad7AssessmentId, String policyVersion,
			SupportTier tier, SupportReasonCode primaryReason, SupportReasonCode secondaryReason,
			String idempotencyKey, String requestHash, Instant evaluatedAt) { }

	record Meaning(String code, String contentVersion, int referencePeriodDays, String text, String limitation) { }
	record Guidance(String code, String contentVersion, String text, String boundary, String safetyGuidance) { }
}

package com.mentalbridge.care.support;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mentalbridge.care.assessment.SafetyStatus;
import com.mentalbridge.care.assessment.ScreeningLevel;

interface SupportEvaluationV2Repository extends JpaRepository<SupportEvaluationV2Entity, UUID> {

	@Query(value = """
			select s.id as "assessmentId", s.definition_id as "definitionId", s.voided_at as "voidedAt",
			       d.instrument as "instrument", d.version as "questionnaireVersion",
			       r.screening_level as "screeningLevel", r.scoring_version as "scoringVersion",
			       r.safety_status as "safetyStatus", r.safety_policy_version as "safetyPolicyVersion"
			from assessment_submission s
			join questionnaire_definition d on d.id = s.definition_id
			left join assessment_result r on r.submission_id = s.id
			where s.id = :assessmentId and s.user_id = :userId
			""", nativeQuery = true)
	Optional<EvidenceRow> findEvidenceRow(@Param("userId") UUID userId, @Param("assessmentId") UUID assessmentId);

	default Optional<Evidence> findEvidence(UUID userId, UUID assessmentId) {
		return findEvidenceRow(userId, assessmentId).map(row -> new Evidence(row.getAssessmentId(),
				row.getDefinitionId(), row.getVoidedAt(), row.getInstrument(), row.getQuestionnaireVersion(),
				row.getScreeningLevel() == null ? null : ScreeningLevel.valueOf(row.getScreeningLevel()),
				row.getScoringVersion(), row.getSafetyStatus() == null ? null : SafetyStatus.valueOf(row.getSafetyStatus()),
				row.getSafetyPolicyVersion()));
	}

	@Query(value = """
			select version from support_evaluation_v2_policy_definition
			where locale = 'vi-VN' and status = 'PUBLISHED'
			""", nativeQuery = true)
	Optional<String> currentPolicyVersion();

	@Query(value = """
			select exists (
				select 1 from support_evaluation_v2_eligible_definition
				where policy_version = :policyVersion and definition_id = :definitionId
				  and instrument = :instrument and questionnaire_version = :questionnaireVersion
				  and scoring_version = :scoringVersion and domain = :domain
			)
			""", nativeQuery = true)
	boolean isEligible(@Param("policyVersion") String policyVersion, @Param("definitionId") UUID definitionId,
			@Param("instrument") String instrument, @Param("questionnaireVersion") String questionnaireVersion,
			@Param("scoringVersion") String scoringVersion, @Param("domain") String domain);

	default boolean isEligible(String policyVersion, Evidence evidence, ScreeningDomain domain) {
		return isEligible(policyVersion, evidence.definitionId(), evidence.instrument(), evidence.questionnaireVersion(),
				evidence.scoringVersion(), domain.name());
	}

	@Query(value = """
			select support_evaluation_id as "evaluationId", request_hash as "requestHash"
			from support_evaluation_v2_request
			where user_id = :userId and idempotency_key = :idempotencyKey
			""", nativeQuery = true)
	Optional<EvaluationRequestRow> findRequest(@Param("userId") UUID userId,
			@Param("idempotencyKey") String idempotencyKey);

	Optional<SupportEvaluationV2Entity> findByUserIdAndPhq9AssessmentIdAndGad7AssessmentIdAndPolicyVersion(
			UUID userId, UUID phq9AssessmentId, UUID gad7AssessmentId, String policyVersion);

	Optional<SupportEvaluationV2Entity> findByIdAndUserId(UUID id, UUID userId);

	@Modifying
	@Query(value = """
			insert into support_evaluation_v2_request
				(user_id,idempotency_key,request_hash,support_evaluation_id,created_at)
			values (:userId,:idempotencyKey,:requestHash,:evaluationId,:createdAt)
			""", nativeQuery = true)
	int insertRequest(@Param("userId") UUID userId, @Param("idempotencyKey") String idempotencyKey,
			@Param("requestHash") String requestHash, @Param("evaluationId") UUID evaluationId,
			@Param("createdAt") Instant createdAt);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			insert into outbox_event (id,message_type,schema_version,aggregate_type,aggregate_id,
				aggregate_version,correlation_id,payload,occurred_at,attempt_count,created_at)
			values (:eventId,'care.support-evaluation.created','2.0','SUPPORT_EVALUATION_V2',:aggregateId,
				0,:correlationId,cast(:payload as jsonb),:occurredAt,0,:occurredAt)
			""", nativeQuery = true)
	int insertOutbox(@Param("eventId") UUID eventId, @Param("aggregateId") UUID aggregateId,
			@Param("correlationId") UUID correlationId, @Param("payload") String payload,
			@Param("occurredAt") Instant occurredAt);

	default void appendOutbox(SupportEvaluationV2Entity evaluation, UUID correlationId, String payload) {
		insertOutbox(UUID.randomUUID(), evaluation.id(), correlationId, payload, evaluation.evaluatedAt());
	}

	interface EvidenceRow {
		UUID getAssessmentId();
		UUID getDefinitionId();
		Instant getVoidedAt();
		String getInstrument();
		String getQuestionnaireVersion();
		String getScreeningLevel();
		String getScoringVersion();
		String getSafetyStatus();
		String getSafetyPolicyVersion();
	}

	interface EvaluationRequestRow {
		UUID getEvaluationId();
		String getRequestHash();
	}

	record Evidence(UUID assessmentId, UUID definitionId, Instant voidedAt, String instrument,
			String questionnaireVersion, ScreeningLevel screeningLevel, String scoringVersion,
			SafetyStatus safetyStatus, String safetyPolicyVersion) { }
}

package com.mentalbridge.care.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.mentalbridge.care.TestcontainersConfiguration;
import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.support.SupportEvaluationService;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CareLiquibaseMigrationTests extends CareTestProperties {

	private static final UUID PHQ9_DEFINITION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID PHQ9_ITEM_1_ID = UUID.fromString("11000000-0000-0000-0000-000000000001");
	private static final UUID PHQ9_VI_DEFINITION_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final UUID GAD7_VI_DEFINITION_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
	private static final UUID PHQ9_VI_V2_DEFINITION_ID = UUID.fromString("10000000-0000-0000-0000-000000000004");

	@Autowired
	private JdbcClient jdbc;

	@Test
	void migrationPublishesVersionedCombinedSupportPolicyAndReviewedSafetyFallback() {
		var policy = jdbc.sql("""
				select version || '|' || locale || '|' || status
				from support_policy_definition where status='PUBLISHED'
				""").query(String.class).single();
		var eligible = jdbc.sql("""
				select instrument || '|' || questionnaire_version || '|' || scoring_version
				from support_policy_eligible_definition order by instrument,questionnaire_version
				""").query(String.class).list();
		var safety = jdbc.sql("""
				select safety_guidance_text from support_tier_guidance
				where policy_version='mb-support-routing-capstone-v1'
				and support_tier='SAFETY_FOLLOW_UP_RECOMMENDED'
				""").query(String.class).single();

		assertThat(policy).isEqualTo("mb-support-routing-capstone-v1|vi-VN|PUBLISHED");
		assertThat(eligible).containsExactly(
				"GAD7|gad7-vi-vn-adult-v1|gad7-standard-bands-v1",
				"PHQ9|phq9-vi-vn-capstone-v1|phq9-standard-bands-v1",
				"PHQ9|phq9-vi-vn-capstone-v2|phq9-standard-bands-v1");
		assertThat(jdbc.sql("select count(*) from screening_band_meaning").query(Long.class).single()).isEqualTo(9);
		assertThat(safety).isEqualTo(SupportEvaluationService.SAFETY_FALLBACK);
	}

	@Test
	void migrationCreatesOwnerTablesInPublicAndSeedsACompletePhq9Definition() {
		var tables = jdbc.sql("""
				select table_name
				from information_schema.tables
				where table_schema = 'public'
				""").query(String.class).list();
		var careSchemaCount = jdbc.sql("""
				select count(*)
				from information_schema.schemata
				where schema_name = 'care'
				""").query(Long.class).single();
		var itemNumbers = jdbc.sql("""
				select item_number
				from questionnaire_question
				where definition_id = :definitionId
				order by item_number
				""").param("definitionId", PHQ9_DEFINITION_ID).query(Integer.class).list();
		var safetyItems = jdbc.sql("""
				select item_number
				from questionnaire_question
				where definition_id = :definitionId and safety_item
				""").param("definitionId", PHQ9_DEFINITION_ID).query(Integer.class).list();
		var bands = jdbc.sql("""
				select code || ':' || minimum_score || '-' || maximum_score
				from questionnaire_score_band
				where definition_id = :definitionId
				order by ordinal
				""").param("definitionId", PHQ9_DEFINITION_ID).query(String.class).list();

		assertThat(tables).contains(
				"user_profile", "consent_decision", "anonymous_assessment_session",
				"questionnaire_definition", "questionnaire_question", "questionnaire_score_band",
				"assessment_submission", "assessment_answer", "assessment_result", "outbox_event",
				"support_policy_definition", "support_policy_eligible_definition", "screening_band_meaning",
				"support_tier_guidance", "support_evaluation", "support_evaluation_request");
		assertThat(careSchemaCount).isZero();
		assertThat(itemNumbers).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
		assertThat(safetyItems).containsExactly(9);
		assertThat(bands).containsExactly(
				"MINIMAL:0-4", "MILD:5-9", "MODERATE:10-14",
				"MODERATELY_SEVERE:15-19", "SEVERE:20-27");
	}

	@Test
	void migrationRetiresPhq9V1AndPublishesTraceableCorrectedV2() {
		var definition = jdbc.sql("""
				select version || '|' || locale || '|' || status || '|' || scoring_version
				from questionnaire_definition where id = :definitionId
				""").param("definitionId", PHQ9_VI_DEFINITION_ID).query(String.class).single();
		var responseOptions = jdbc.sql("""
				select (option ->> 'value') || ':' || (option ->> 'label')
				from questionnaire_definition,
					 lateral jsonb_array_elements(response_options) with ordinality as entry(option, ordinal)
				where id = :definitionId
				order by entry.ordinal
				""").param("definitionId", PHQ9_VI_DEFINITION_ID).query(String.class).list();
		var questions = jdbc.sql("""
				select item_number || ':' || prompt
				from questionnaire_question
				where definition_id = :definitionId
				order by item_number
				""").param("definitionId", PHQ9_VI_DEFINITION_ID).query(String.class).list();
		var safetyItems = jdbc.sql("""
				select item_number from questionnaire_question
				where definition_id = :definitionId and safety_item
				""").param("definitionId", PHQ9_VI_DEFINITION_ID).query(Integer.class).list();
		var bands = jdbc.sql("""
				select code || ':' || minimum_score || '-' || maximum_score
				from questionnaire_score_band
				where definition_id = :definitionId order by ordinal
				""").param("definitionId", PHQ9_VI_DEFINITION_ID).query(String.class).list();
		var source = jdbc.sql("select source_reference from questionnaire_definition where id = :definitionId")
				.param("definitionId", PHQ9_VI_DEFINITION_ID).query(String.class).single();

		assertThat(definition).isEqualTo("phq9-vi-vn-capstone-v1|vi-VN|RETIRED|phq9-standard-bands-v1");
		assertThat(responseOptions).containsExactly(
				"0:Không có gì", "1:Vài ngày", "2:Hơn nửa ngày", "3:Gần như mỗi ngày");
		assertThat(questions).hasSize(9)
				.first().isEqualTo("1:Ít quan tâm hoặc niềm vui khi làm việc");
		assertThat(questions.get(8))
				.isEqualTo("9:Suy nghĩ rằng tốt hơn hết là bạn nên chết hoặc làm tổn thương bản thân theo một cách nào đó");
		assertThat(safetyItems).containsExactly(9);
		assertThat(bands).containsExactly(
				"MINIMAL:0-4", "MILD:5-9", "MODERATE:10-14",
				"MODERATELY_SEVERE:15-19", "SEVERE:20-27");
		assertThat(source).contains(
				"20240720104123",
				"E2775444E5AB4A05C3FF097F1CAB356C2DA9ECC73BAC63E91827BAA77E965FF7",
				"no permission is required");

		var corrected = jdbc.sql("""
				select version || '|' || status || '|' || scoring_version
				from questionnaire_definition where id = :definitionId
				""").param("definitionId", PHQ9_VI_V2_DEFINITION_ID).query(String.class).single();
		var correctedQuestion = jdbc.sql("""
				select prompt from questionnaire_question
				where definition_id = :definitionId and item_number = 2
				""").param("definitionId", PHQ9_VI_V2_DEFINITION_ID).query(String.class).single();
		var correctedSource = jdbc.sql("select source_reference from questionnaire_definition where id = :definitionId")
				.param("definitionId", PHQ9_VI_V2_DEFINITION_ID).query(String.class).single();
		assertThat(corrected).isEqualTo("phq9-vi-vn-capstone-v2|PUBLISHED|phq9-standard-bands-v1");
		assertThat(correctedQuestion).isEqualTo("Cảm thấy chán nản, buồn rầu hoặc vô vọng");
		assertThat(correctedSource).contains("Product Owner", "not claimed verbatim");
	}

	@Test
	void migrationPublishesExactGad7MappingAndBands() {
		var definition = jdbc.sql("""
				select version || '|' || locale || '|' || status || '|' || scoring_version
				from questionnaire_definition where id = :definitionId
				""").param("definitionId", GAD7_VI_DEFINITION_ID).query(String.class).single();
		var options = jdbc.sql("""
				select (option ->> 'value') || ':' || (option ->> 'label')
				from questionnaire_definition,
				     lateral jsonb_array_elements(response_options) with ordinality as entry(option, ordinal)
				where id = :definitionId order by entry.ordinal
				""").param("definitionId", GAD7_VI_DEFINITION_ID).query(String.class).list();
		var questions = jdbc.sql("""
				select prompt from questionnaire_question
				where definition_id = :definitionId order by item_number
				""").param("definitionId", GAD7_VI_DEFINITION_ID).query(String.class).list();
		var bands = jdbc.sql("""
				select code || ':' || minimum_score || '-' || maximum_score
				from questionnaire_score_band where definition_id = :definitionId order by ordinal
				""").param("definitionId", GAD7_VI_DEFINITION_ID).query(String.class).list();
		var source = jdbc.sql("select source_reference from questionnaire_definition where id = :definitionId")
				.param("definitionId", GAD7_VI_DEFINITION_ID).query(String.class).single();

		assertThat(definition).isEqualTo("gad7-vi-vn-adult-v1|vi-VN|PUBLISHED|gad7-standard-bands-v1");
		assertThat(options).containsExactly(
				"0:Không bao giờ (0 ngày nào)", "1:Vài ngày (1-7 ngày)",
				"2:Hơn một nửa số ngày (8-10 ngày)", "3:Gần như hàng ngày (11-14 ngày)");
		assertThat(questions).hasSize(7)
				.first().isEqualTo("Cảm giác hồi hộp, lo lắng hoặc cáu kỉnh");
		assertThat(bands).containsExactly("MINIMAL:0-4", "MILD:5-9", "MODERATE:10-14", "SEVERE:15-21");
		assertThat(source).contains(
				"UNC Vietnam 2024", "retrieved 2026-09-09",
				"876A7245EF7BDDFC3EADFA15625E02F132560218C219E4E5251E6B7DC6A8A001",
				"codes 88/99 excluded");
	}

	@Test
	void consentHistoryIsAppendOnlyAndIdempotencyIsScopedByUserAndType() {
		var userId = insertProfile();
		insertConsent(userId, "consent-key-00000001", "a".repeat(64), true);
		insertConsent(userId, "consent-key-00000002", "b".repeat(64), false);

		var historyCount = jdbc.sql("""
				select count(*) from consent_decision
				where user_id = :userId and consent_type = 'AI_PROCESSING'
				""").param("userId", userId).query(Long.class).single();

		assertThat(historyCount).isEqualTo(2);
		assertThatThrownBy(() -> insertConsent(userId, "consent-key-00000001", "c".repeat(64), false))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void assessmentOwnerAndIdempotencyConstraintsPreventMixedOrDuplicateOwnership() {
		var userId = insertProfile();
		var sessionId = insertAnonymousSession();
		insertAuthenticatedSubmission(userId, "assessment-key-000001");

		assertThatThrownBy(() -> insertAuthenticatedSubmission(userId, "assessment-key-000001"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("""
				insert into assessment_submission (
					user_id, anonymous_session_id, definition_id, idempotency_key,
					request_hash, privacy_policy_version, submitted_at, retention_expires_at
				) values (
					:userId, :sessionId, :definitionId, :idempotencyKey,
					:requestHash, 'privacy-capstone-v1', :submittedAt, :expiresAt
				)
				""").param("userId", userId)
				.param("sessionId", sessionId)
				.param("definitionId", PHQ9_DEFINITION_ID)
				.param("idempotencyKey", "mixed-owner-key-0001")
				.param("requestHash", "d".repeat(64))
				.param("submittedAt", OffsetDateTime.now())
				.param("expiresAt", OffsetDateTime.now().plusMinutes(30))
				.update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("""
				insert into assessment_submission (
					anonymous_session_id, definition_id, idempotency_key,
					request_hash, privacy_policy_version, submitted_at, retention_expires_at
				) values (
					:sessionId, :definitionId, :idempotencyKey,
					:requestHash, 'privacy-capstone-v1', :submittedAt, :expiresAt
				)
				""").param("sessionId", sessionId)
				.param("definitionId", PHQ9_DEFINITION_ID)
				.param("idempotencyKey", "expired-owner-key-01")
				.param("requestHash", "f".repeat(64))
				.param("submittedAt", OffsetDateTime.now())
				.param("expiresAt", OffsetDateTime.now().minusMinutes(1))
				.update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("""
				insert into anonymous_assessment_session (token_hash, expires_at, created_at)
				values (:tokenHash, :expiresAt, :createdAt)
				""").param("tokenHash", "1".repeat(64))
				.param("expiresAt", OffsetDateTime.now().minusMinutes(1))
				.param("createdAt", OffsetDateTime.now())
				.update()).isInstanceOf(DataIntegrityViolationException.class);

		var sessionColumns = jdbc.sql("""
				select column_name
				from information_schema.columns
				where table_schema = 'public' and table_name = 'anonymous_assessment_session'
				""").query(String.class).list();
		assertThat(sessionColumns).doesNotContain("user_id", "account_id", "claimed_by_user_id");
	}

	@Test
	void answerAndResultConstraintsRejectClientOwnedInvalidValues() {
		var userId = insertProfile();
		var submissionId = insertAuthenticatedSubmission(userId, "assessment-key-000002");

		assertThatThrownBy(() -> jdbc.sql("""
				insert into assessment_answer (submission_id, definition_id, question_id, answer_value)
				values (:submissionId, :definitionId, :questionId, 4)
				""").param("submissionId", submissionId)
				.param("definitionId", PHQ9_DEFINITION_ID)
				.param("questionId", PHQ9_ITEM_1_ID)
				.update()).isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> jdbc.sql("""
				insert into assessment_result (
					submission_id, total_score, screening_level, scoring_version,
					safety_item_positive, calculated_at
				) values (
					:submissionId, 28, 'SEVERE', 'phq9-standard-bands-v1', false, :calculatedAt
				)
				""").param("submissionId", submissionId)
				.param("calculatedAt", OffsetDateTime.now())
				.update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void assessmentResultKeepsSafetyStatusIndependentAndVersioned() {
		var userId = insertProfile();
		var validSubmissionId = insertAuthenticatedSubmission(userId, "assessment-key-000010");

		jdbc.sql("""
				insert into assessment_result (
					submission_id, total_score, screening_level, scoring_version,
					safety_item_positive, safety_status, safety_policy_version, calculated_at
				) values (
					:submissionId, 8, 'MILD', 'phq9-standard-bands-v1',
					true, 'POSITIVE_SAFETY_SCREEN', 'test-safety-policy-v1', :calculatedAt
				)
				""").param("submissionId", validSubmissionId)
				.param("calculatedAt", OffsetDateTime.now())
				.update();

		assertThat(jdbc.sql("""
				select screening_level, safety_status, safety_policy_version
				from assessment_result where submission_id = :submissionId
				""").param("submissionId", validSubmissionId)
				.query((resultSet, rowNum) -> String.join("|",
						resultSet.getString("screening_level"),
						resultSet.getString("safety_status"),
						resultSet.getString("safety_policy_version")))
				.single()).isEqualTo("MILD|POSITIVE_SAFETY_SCREEN|test-safety-policy-v1");

		var mismatchedSubmissionId = insertAuthenticatedSubmission(userId, "assessment-key-000011");
		assertThatThrownBy(() -> jdbc.sql("""
				insert into assessment_result (
					submission_id, total_score, screening_level, scoring_version,
					safety_item_positive, safety_status, safety_policy_version, calculated_at
				) values (
					:submissionId, 8, 'MILD', 'phq9-standard-bands-v1',
					false, 'POSITIVE_SAFETY_SCREEN', 'test-safety-policy-v1', :calculatedAt
				)
				""").param("submissionId", mismatchedSubmissionId)
				.param("calculatedAt", OffsetDateTime.now())
				.update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void assessmentResultRepresentsGad7SafetyAsNotApplicableWithoutAFalseFlagOrPolicy() {
		var userId = insertProfile();
		var submissionId = insertAuthenticatedSubmission(userId, "gad7-safety-na-000001", GAD7_VI_DEFINITION_ID);

		jdbc.sql("""
				insert into assessment_result (
				    submission_id, total_score, screening_level, scoring_version,
				    safety_item_positive, safety_status, safety_policy_version, calculated_at
				) values (
				    :submissionId, 10, 'MODERATE', 'gad7-standard-bands-v1',
				    null, 'NOT_APPLICABLE', null, :calculatedAt
				)
				""").param("submissionId", submissionId)
				.param("calculatedAt", OffsetDateTime.now()).update();

		assertThatThrownBy(() -> {
			var invalidSubmissionId = insertAuthenticatedSubmission(userId, "gad7-safety-false-001", GAD7_VI_DEFINITION_ID);
			jdbc.sql("""
					insert into assessment_result (
					    submission_id, total_score, screening_level, scoring_version,
					    safety_item_positive, safety_status, safety_policy_version, calculated_at
					) values (
					    :submissionId, 10, 'MODERATE', 'gad7-standard-bands-v1',
					    false, 'NOT_APPLICABLE', null, :calculatedAt
					)
					""").param("submissionId", invalidSubmissionId)
					.param("calculatedAt", OffsetDateTime.now()).update();
		}).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void answersCannotCrossQuestionnaireVersions() {
		var otherDefinitionId = jdbc.sql("""
				insert into questionnaire_definition (
					instrument, version, locale, title, reference_period_days,
					expected_question_count, scoring_version, response_options,
					source_reference
				) values (
					'PHQ9', :version, 'en-US', 'Test draft', 14,
					9, 'test-v1', '[0,1,2,3]'::jsonb, 'test-only'
				)
				returning id
				""").param("version", "draft-" + UUID.randomUUID().toString().substring(0, 8))
				.query(UUID.class).single();
		var otherQuestionId = jdbc.sql("""
				insert into questionnaire_question (definition_id, item_number, prompt)
				values (:definitionId, 1, 'Test-only question')
				returning id
				""").param("definitionId", otherDefinitionId).query(UUID.class).single();
		var submissionId = insertAuthenticatedSubmission(insertProfile(), "assessment-key-000003");

		assertThatThrownBy(() -> jdbc.sql("""
				insert into assessment_answer (submission_id, definition_id, question_id, answer_value)
				values (:submissionId, :definitionId, :questionId, 0)
				""").param("submissionId", submissionId)
				.param("definitionId", otherDefinitionId)
				.param("questionId", otherQuestionId)
				.update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void questionnaireVersionsAndOutboxPayloadShapeAreConstrained() {
		assertThatThrownBy(() -> jdbc.sql("""
				insert into questionnaire_definition (
					instrument, version, locale, title, reference_period_days,
					expected_question_count, scoring_version, response_options,
					source_reference, status, published_at
				) values (
					'PHQ9', 'phq9-en-us-v1', 'en-US', 'Duplicate', 14,
					9, 'duplicate', '[0,1,2,3]'::jsonb, 'duplicate', 'DRAFT', null
				)
				""").update()).isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> jdbc.sql("""
				insert into outbox_event (
					message_type, schema_version, aggregate_type, aggregate_id,
					aggregate_version, correlation_id, payload, occurred_at
				) values (
					'AssessmentSubmitted', '1', 'ASSESSMENT', :aggregateId,
					0, :correlationId, '[]'::jsonb, :occurredAt
				)
				""").param("aggregateId", UUID.randomUUID())
				.param("correlationId", UUID.randomUUID())
				.param("occurredAt", OffsetDateTime.now())
				.update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	private UUID insertProfile() {
		return jdbc.sql("""
				insert into user_profile (account_id, display_name)
				values (:accountId, 'Care migration test')
				returning account_id
				""").param("accountId", UUID.randomUUID()).query(UUID.class).single();
	}

	private void insertConsent(UUID userId, String idempotencyKey, String requestHash, boolean granted) {
		jdbc.sql("""
				insert into consent_decision (
					user_id, consent_type, policy_version, granted, idempotency_key,
					request_hash, decided_at
				) values (
					:userId, 'AI_PROCESSING', 'test-policy-v1', :granted, :idempotencyKey,
					:requestHash, :decidedAt
				)
				""").param("userId", userId)
				.param("granted", granted)
				.param("idempotencyKey", idempotencyKey)
				.param("requestHash", requestHash)
				.param("decidedAt", OffsetDateTime.now())
				.update();
	}

	private UUID insertAnonymousSession() {
		return jdbc.sql("""
				insert into anonymous_assessment_session (token_hash, expires_at)
				values (:tokenHash, :expiresAt)
				returning id
				""").param("tokenHash", UUID.randomUUID().toString().replace("-", "").repeat(2))
				.param("expiresAt", OffsetDateTime.now().plusHours(1))
				.query(UUID.class).single();
	}

	private UUID insertAuthenticatedSubmission(UUID userId, String idempotencyKey) {
		return insertAuthenticatedSubmission(userId, idempotencyKey, PHQ9_DEFINITION_ID);
	}

	private UUID insertAuthenticatedSubmission(UUID userId, String idempotencyKey, UUID definitionId) {
		return jdbc.sql("""
				insert into assessment_submission (
					user_id, definition_id, idempotency_key, request_hash, privacy_policy_version, submitted_at
				) values (
					:userId, :definitionId, :idempotencyKey, :requestHash, 'privacy-capstone-v1', :submittedAt
				)
				returning id
				""").param("userId", userId)
				.param("definitionId", definitionId)
				.param("idempotencyKey", idempotencyKey)
				.param("requestHash", "e".repeat(64))
				.param("submittedAt", OffsetDateTime.now())
				.query(UUID.class).single();
	}

}

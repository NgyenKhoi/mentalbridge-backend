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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CareLiquibaseMigrationTests {

	private static final UUID PHQ9_DEFINITION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID PHQ9_ITEM_1_ID = UUID.fromString("11000000-0000-0000-0000-000000000001");

	@Autowired
	private JdbcClient jdbc;

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
				"assessment_submission", "assessment_answer", "assessment_result", "outbox_event");
		assertThat(careSchemaCount).isZero();
		assertThat(itemNumbers).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
		assertThat(safetyItems).containsExactly(9);
		assertThat(bands).containsExactly(
				"MINIMAL:0-4", "MILD:5-9", "MODERATE:10-14",
				"MODERATELY_SEVERE:15-19", "SEVERE:20-27");
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
					request_hash, submitted_at, retention_expires_at
				) values (
					:userId, :sessionId, :definitionId, :idempotencyKey,
					:requestHash, :submittedAt, :expiresAt
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
					request_hash, submitted_at, retention_expires_at
				) values (
					:sessionId, :definitionId, :idempotencyKey,
					:requestHash, :submittedAt, :expiresAt
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
		return jdbc.sql("""
				insert into assessment_submission (
					user_id, definition_id, idempotency_key, request_hash, submitted_at
				) values (
					:userId, :definitionId, :idempotencyKey, :requestHash, :submittedAt
				)
				returning id
				""").param("userId", userId)
				.param("definitionId", PHQ9_DEFINITION_ID)
				.param("idempotencyKey", idempotencyKey)
				.param("requestHash", "e".repeat(64))
				.param("submittedAt", OffsetDateTime.now())
				.query(UUID.class).single();
	}

}

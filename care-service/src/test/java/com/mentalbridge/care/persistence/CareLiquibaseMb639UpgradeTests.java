package com.mentalbridge.care.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

@Testcontainers
class CareLiquibaseMb639UpgradeTests {

	private static final String USER_ID = "60000000-0000-4000-8000-000000000001";
	private static final String PHQ9_ASSESSMENT_ID = "60000000-0000-4000-8000-000000000002";
	private static final String GAD7_ASSESSMENT_ID = "60000000-0000-4000-8000-000000000003";
	private static final String EVALUATION_ID = "60000000-0000-4000-8000-000000000004";
	private static final String PLAN_ID = "60000000-0000-4000-8000-000000000005";
	private static final String SLOT_ID = "60000000-0000-4000-8000-000000000006";
	private static final String SCHEDULE_ID = "60000000-0000-4000-8000-000000000007";
	private static final String OCCURRENCE_ID = "60000000-0000-4000-8000-000000000008";
	private static final String RESOURCE_ID = "60000000-0000-4000-8000-000000000009";
	private static final String PUBLICATION_ID = "60000000-0000-4000-8000-000000000010";
	private static final String NATIVE_PLAN_ID = "61000000-0000-4000-8000-000000000001";
	private static final String NATIVE_FOCUS_ID = "61000000-0000-4000-8000-000000000002";
	private static final String NATIVE_ACTION_ID = "61000000-0000-4000-8000-000000000003";

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse("postgres:latest"));

	@Test
	void upgradesExistingV2PlanWithoutRewritingHistoryAndEnforcesNativeV3Ownership() throws Exception {
		migrate("db/changelog/db.changelog-pre-mb639.yaml");
		seedExistingV2PlanHistory();

		var planBefore = legacyPlanSnapshot();
		var slotBefore = slotSnapshot();
		var scheduleBefore = legacyScheduleSnapshot();
		var occurrenceBefore = legacyOccurrenceSnapshot();

		migrate("db/changelog/db.changelog-master.yaml");

		assertThat(legacyPlanSnapshot()).isEqualTo(planBefore);
		assertThat(slotSnapshot()).isEqualTo(slotBefore);
		assertThat(legacyScheduleSnapshot()).isEqualTo(scheduleBefore);
		assertThat(legacyOccurrenceSnapshot()).isEqualTo(occurrenceBefore);
		assertThat(scalar("""
				select model_version || '|' || coalesce(plan_policy_version, '<null>') || '|' || action_count
				from support_plan where id = '%s'
				""".formatted(PLAN_ID))).isEqualTo("2|<null>|0");
		assertThat(scalar("""
				select (source_action_id is null) || '|' || (source_action_version is null) || '|'
				       || (source_action_type is null) || '|' || (schedule_policy_version is null) || '|'
				       || reminder_enabled
				from support_plan_activity_schedule where id = '%s'
				""".formatted(SCHEDULE_ID))).isEqualTo("true|true|true|true|false");
		assertThat(scalar("""
				select (source_action_id is null) || '|' || (source_action_version is null) || '|'
				       || (source_action_type is null) || '|' || (evidence_owner is null) || '|'
				       || (evidence_kind is null) || '|' || (evidence_id is null) || '|'
				       || (evidence_revision is null) || '|' || (evidence_occurred_at is null) || '|'
				       || (evidence_verified_at is null)
				from support_plan_activity_occurrence where id = '%s'
				""".formatted(OCCURRENCE_ID)))
				.isEqualTo("true|true|true|true|true|true|true|true|true");
		assertThat(scalar("select count(*) from support_plan_focus")).isEqualTo("0");
		assertThat(scalar("select count(*) from support_plan_action")).isEqualTo("0");
		assertThat(scalar("""
				select (personalization_id is null) || '|' || (personalization_revision is null) || '|'
				       || (personalization_schema_version is null)
				from support_plan where id = '%s'
				""".formatted(PLAN_ID))).isEqualTo("true|true|true");

		insertNativeV3ResourceAction();
		assertThat(scalar("""
				select sp.model_version || '|' || sp.plan_policy_version || '|' || sp.action_count || '|'
				       || f.ordinal || '|' || a.action_type || '|' || a.source_owner || '|' || a.source_revision
				from support_plan sp
				join support_plan_focus f on f.support_plan_id = sp.id
				join support_plan_action a on a.plan_focus_id = f.id
				where sp.id = '%s'
				""".formatted(NATIVE_PLAN_ID)))
				.isEqualTo("3|mb-support-plan-v3|1|1|RESOURCE|CONTENT|7");
		assertThatThrownBy(this::insertCrossOwnerNativeAction)
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("ck_support_plan_action_source");
	}

	private void migrate(String changelog) throws Exception {
		try (Connection connection = connection();
				Liquibase liquibase = new Liquibase(changelog, new ClassLoaderResourceAccessor(),
						new JdbcConnection(connection))) {
			liquibase.update();
		}
	}

	private void seedExistingV2PlanHistory() throws SQLException {
		executeUpdate("""
				insert into user_profile (account_id, display_name)
				values ('%s', 'MB-639 upgrade user')
				""".formatted(USER_ID));
		executeUpdate("""
				insert into assessment_submission (
				  id, user_id, definition_id, idempotency_key, request_hash,
				  privacy_policy_version, submitted_at
				) values (
				  '%s', '%s', '10000000-0000-0000-0000-000000000004',
				  'mb639-upgrade-phq9-0001', '%s', 'privacy-capstone-v3', '2026-09-29T01:00:00Z'
				)
				""".formatted(PHQ9_ASSESSMENT_ID, USER_ID, "a".repeat(64)));
		executeUpdate("""
				insert into assessment_submission (
				  id, user_id, definition_id, idempotency_key, request_hash,
				  privacy_policy_version, submitted_at
				) values (
				  '%s', '%s', '10000000-0000-0000-0000-000000000003',
				  'mb639-upgrade-gad7-0001', '%s', 'privacy-capstone-v3', '2026-09-29T01:05:00Z'
				)
				""".formatted(GAD7_ASSESSMENT_ID, USER_ID, "b".repeat(64)));
		executeUpdate("""
				insert into support_evaluation_v2 (
				  id, user_id, phq9_assessment_id, gad7_assessment_id, policy_version, evaluated_at
				) values (
				  '%s', '%s', '%s', '%s', 'mb-support-routing-capstone-v2', '2026-09-29T01:10:00Z'
				)
				""".formatted(EVALUATION_ID, USER_ID, PHQ9_ASSESSMENT_ID, GAD7_ASSESSMENT_ID));
		executeUpdate("""
				insert into support_plan (
				  id, user_id, support_evaluation_id, status, version, evaluation_policy_version, evaluated_at,
				  selection_policy_version, resource_policy_version, resources_resolved_at, entitlement_package,
				  entitlement_source, entitlement_policy_version, entitlement_version, entitlement_decided_at,
				  rationale_code, rationale_text, safety_status, safety_reason_code, safety_policy_version,
				  safety_guidance_code, safety_guidance, selected_resource_count, created_at, updated_at, activated_at
				) values (
				  '%s', '%s', '%s', 'ACTIVE', 1, 'mb-support-routing-capstone-v2', '2026-09-29T01:10:00Z',
				  'mb-support-plan-selection-v1', 'content-eligibility-v1', '2026-09-29T01:15:00Z', 'PLUS',
				  'DEMO', 'service-entitlement-v1', 3, '2026-09-29T01:12:00Z',
				  'DOMAIN_BALANCED', 'Keep both domains visible', 'NEGATIVE_SAFETY_SCREEN',
				  'PHQ9_ITEM9_NEGATIVE', 'MB-SAFETY-PHQ9-001-v1', 'STANDARD_SAFETY_REMINDER',
				  'Use urgent support when needed', 1, '2026-09-29T01:15:00Z',
				  '2026-09-29T01:20:00Z', '2026-09-29T01:20:00Z'
				)
				""".formatted(PLAN_ID, USER_ID, EVALUATION_ID));
		executeUpdate("""
				insert into support_plan_slot (
				  id, support_plan_id, ordinal, slot_key, slot_kind, target_domain, purpose_code,
				  selected_resource_id, selected_content_version, selected_publication_id, selected_role,
				  selected_category, selected_title, selected_summary, selected_external_url
				) values (
				  '%s', '%s', 1, 'depressive-breathing', 'CORE', 'DEPRESSIVE_SYMPTOMS', 'CALMING_PRACTICE',
				  '%s', 7, '%s', 'PRIMARY', 'BREATHING', 'Breathing pause',
				  'A short reviewed breathing practice', 'https://example.test/resources/breathing-pause'
				)
				""".formatted(SLOT_ID, PLAN_ID, RESOURCE_ID, PUBLICATION_ID));
		executeUpdate("""
				insert into support_plan_activity_schedule (
				  id, support_plan_id, user_id, ordinal, schedule_version, source_plan_version, source_slot_key,
				  source_resource_id, source_content_version, source_title, recurrence_type, local_time, timezone,
				  effective_from, status, created_at, updated_at
				) values (
				  '%s', '%s', '%s', 1, 1, 1, 'depressive-breathing', '%s', 7, 'Breathing pause',
				  'DAILY', '08:30', 'Asia/Bangkok', '2026-10-01', 'ACTIVE',
				  '2026-09-29T01:25:00Z', '2026-09-29T01:25:00Z'
				)
				""".formatted(SCHEDULE_ID, PLAN_ID, USER_ID, RESOURCE_ID));
		executeUpdate("""
				insert into support_plan_activity_occurrence (
				  id, activity_schedule_id, support_plan_id, user_id, schedule_version, local_date, local_time,
				  timezone, scheduled_at, state, source_plan_version, source_slot_key, source_resource_id,
				  source_content_version, source_title, version, created_at, updated_at, completed_at,
				  hidden, helpfulness, reflection, summary_reuse_approved, engagement_updated_at
				) values (
				  '%s', '%s', '%s', '%s', 1, '2026-10-01', '08:30', 'Asia/Bangkok',
				  '2026-10-01T01:30:00Z', 'COMPLETED', 1, 'depressive-breathing', '%s', 7,
				  'Breathing pause', 2, '2026-10-01T01:30:00Z', '2026-10-01T01:40:00Z',
				  '2026-10-01T01:40:00Z', false, 'HELPFUL', 'Helped me pause.', true,
				  '2026-10-01T01:40:00Z'
				)
				""".formatted(OCCURRENCE_ID, SCHEDULE_ID, PLAN_ID, USER_ID, RESOURCE_ID));
	}

	private void insertNativeV3ResourceAction() throws SQLException {
		executeUpdate("""
				insert into support_plan (
				  id, user_id, support_evaluation_id, status, version, evaluation_policy_version, evaluated_at,
				  entitlement_package, entitlement_source, entitlement_policy_version, entitlement_version,
				  entitlement_decided_at, rationale_code, rationale_text, safety_status, safety_reason_code,
				  safety_policy_version, safety_guidance_code, safety_guidance, created_at, updated_at,
				  model_version, plan_policy_version, action_count
				) values (
				  '%s', '%s', '%s', 'DRAFT', 0, 'mb-support-routing-capstone-v2', '2026-10-02T01:00:00Z',
				  'PLUS', 'DEMO', 'service-entitlement-v1', 3, '2026-10-02T01:00:00Z',
				  'USER_CONFIRMED_FOCUS', 'A bounded native plan', 'NEGATIVE_SAFETY_SCREEN',
				  'PHQ9_ITEM9_NEGATIVE', 'MB-SAFETY-PHQ9-001-v1', 'STANDARD_SAFETY_REMINDER',
				  'Use urgent support when needed', '2026-10-02T01:00:00Z', '2026-10-02T01:00:00Z',
				  3, 'mb-support-plan-v3', 1
				)
				""".formatted(NATIVE_PLAN_ID, USER_ID, EVALUATION_ID));
		executeUpdate("""
				insert into support_plan_focus (
				  id, support_plan_id, user_id, ordinal, title, rationale, version, created_at, updated_at
				) values (
				  '%s', '%s', '%s', 1, 'Create a calmer pause', 'Chosen for a practical daily step', 0,
				  '2026-10-02T01:00:00Z', '2026-10-02T01:00:00Z'
				)
				""".formatted(NATIVE_FOCUS_ID, NATIVE_PLAN_ID, USER_ID));
		executeUpdate("""
				insert into support_plan_action (
				  id, support_plan_id, plan_focus_id, user_id, ordinal, action_type, purpose_code, rationale,
				  source_owner, source_kind, source_id, source_revision, source_publication_id,
				  source_eligibility_policy_version, source_verified_at, provenance_type,
				  provenance_accepted_at, presentation_title, presentation_summary, status, version,
				  created_at, updated_at
				) values (
				  '%s', '%s', '%s', '%s', 1, 'RESOURCE', 'CALMING_PRACTICE', 'Use exact reviewed content',
				  'CONTENT', 'RESOURCE_VERSION', '%s', '7', '%s', 'content-eligibility-v1',
				  '2026-10-02T01:00:00Z', 'USER_SELECTED', '2026-10-02T01:00:00Z',
				  'Breathing pause', 'A short reviewed breathing practice', 'DRAFT', 0,
				  '2026-10-02T01:00:00Z', '2026-10-02T01:00:00Z'
				)
				""".formatted(NATIVE_ACTION_ID, NATIVE_PLAN_ID, NATIVE_FOCUS_ID, USER_ID,
				RESOURCE_ID, PUBLICATION_ID));
	}

	private void insertCrossOwnerNativeAction() throws SQLException {
		executeUpdate("""
				insert into support_plan_action (
				  id, support_plan_id, plan_focus_id, user_id, ordinal, action_type, purpose_code,
				  source_owner, source_kind, source_id, source_revision, source_publication_id,
				  source_eligibility_policy_version, source_verified_at, provenance_type,
				  provenance_accepted_at, presentation_title, status, version, created_at, updated_at
				) values (
				  '61000000-0000-4000-8000-000000000004', '%s', '%s', '%s', 2, 'JOURNAL', 'REFLECTION',
				  'CONTENT', 'RESOURCE_VERSION', '%s', '7', '%s', 'content-eligibility-v1',
				  '2026-10-02T01:00:00Z', 'USER_SELECTED', '2026-10-02T01:00:00Z',
				  'Journal reflection', 'DRAFT', 0, '2026-10-02T01:00:00Z', '2026-10-02T01:00:00Z'
				)
				""".formatted(NATIVE_PLAN_ID, NATIVE_FOCUS_ID, USER_ID, RESOURCE_ID, PUBLICATION_ID));
	}

	private String legacyPlanSnapshot() throws SQLException {
		return scalar("""
				select (to_jsonb(sp) - 'model_version' - 'plan_policy_version' - 'action_count'
				        - 'personalization_id' - 'personalization_revision'
				        - 'personalization_schema_version')::text
				from support_plan sp where id = '%s'
				""".formatted(PLAN_ID));
	}

	private String slotSnapshot() throws SQLException {
		return scalar("select to_jsonb(sps)::text from support_plan_slot sps where id = '%s'".formatted(SLOT_ID));
	}

	private String legacyScheduleSnapshot() throws SQLException {
		return scalar("""
				select (to_jsonb(s) - 'source_action_id' - 'source_action_version' - 'source_action_type'
				        - 'schedule_policy_version' - 'reminder_enabled')::text
				from support_plan_activity_schedule s where id = '%s'
				""".formatted(SCHEDULE_ID));
	}

	private String legacyOccurrenceSnapshot() throws SQLException {
		return scalar("""
				select (to_jsonb(o) - 'source_action_id' - 'source_action_version' - 'source_action_type'
				        - 'evidence_owner' - 'evidence_kind' - 'evidence_id' - 'evidence_revision'
				        - 'evidence_occurred_at' - 'evidence_verified_at')::text
				from support_plan_activity_occurrence o where id = '%s'
				""".formatted(OCCURRENCE_ID));
	}

	private int executeUpdate(String sql) throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			return statement.executeUpdate(sql);
		}
	}

	private String scalar(String sql) throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			assertThat(result.next()).isTrue();
			return result.getString(1);
		}
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}
}

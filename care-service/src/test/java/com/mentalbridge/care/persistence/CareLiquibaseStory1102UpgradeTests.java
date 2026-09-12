package com.mentalbridge.care.persistence;

import static org.assertj.core.api.Assertions.assertThat;

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
class CareLiquibaseStory1102UpgradeTests {

	private static final String HISTORICAL_ASSESSMENT_ID = "50000000-0000-4000-8000-000000000001";

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse("postgres:latest"));

	@Test
	void upgradesAnExistingDatabaseWithoutRewritingHistoricalPhq9Evidence() throws Exception {
		migrate("db/changelog/db.changelog-pre-story1102.yaml");
		seedHistoricalPhq9V1Assessment();

		var originalQuestion = scalar("""
				select prompt from questionnaire_question
				where definition_id = '10000000-0000-0000-0000-000000000002'
				  and item_number = 2
				""");

		migrate("db/changelog/db.changelog-master.yaml");

		assertThat(scalar("""
				select questionnaire_definition.version || '|' || questionnaire_definition.status || '|'
				       || assessment_submission.privacy_policy_version || '|'
				       || assessment_result.total_score || '|' || assessment_result.safety_status
				from assessment_submission
				join questionnaire_definition on questionnaire_definition.id = assessment_submission.definition_id
				join assessment_result on assessment_result.submission_id = assessment_submission.id
				where assessment_submission.id = '%s'
				""".formatted(HISTORICAL_ASSESSMENT_ID)))
				.isEqualTo("phq9-vi-vn-capstone-v1|RETIRED|privacy-capstone-v2|4|NEGATIVE_SAFETY_SCREEN");
		assertThat(scalar("""
				select prompt from questionnaire_question
				where definition_id = '10000000-0000-0000-0000-000000000002'
				  and item_number = 2
				""")).isEqualTo(originalQuestion);
		assertThat(scalar("""
				select string_agg(instrument || ':' || version || ':' || status, ',' order by instrument, version)
				from questionnaire_definition
				where id in (
				  '10000000-0000-0000-0000-000000000003',
				  '10000000-0000-0000-0000-000000000004'
				)
				""")).isEqualTo(
					"GAD7:gad7-vi-vn-adult-v1:PUBLISHED,PHQ9:phq9-vi-vn-capstone-v2:PUBLISHED");
		assertThat(scalar("""
				select string_agg(instrument || ':' || questionnaire_version, ',' order by instrument, questionnaire_version)
				from support_policy_eligible_definition
				where policy_version = 'mb-support-routing-capstone-v1'
				""")).isEqualTo(
					"GAD7:gad7-vi-vn-adult-v1,PHQ9:phq9-vi-vn-capstone-v1,PHQ9:phq9-vi-vn-capstone-v2");
	}

	private void migrate(String changelog) throws Exception {
		try (Connection connection = connection();
				Liquibase liquibase = new Liquibase(changelog, new ClassLoaderResourceAccessor(),
						new JdbcConnection(connection))) {
			liquibase.update();
		}
	}

	private void seedHistoricalPhq9V1Assessment() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.executeUpdate("""
					insert into user_profile (account_id, display_name)
					values ('50000000-0000-4000-8000-000000000002', 'Historical user')
					""");
			statement.executeUpdate("""
					insert into assessment_submission (
					  id, user_id, definition_id, idempotency_key, request_hash,
					  privacy_policy_version, submitted_at
					) values (
					  '%s', '50000000-0000-4000-8000-000000000002',
					  '10000000-0000-0000-0000-000000000002', 'historical-phq9-v1-0001',
					  '%s', 'privacy-capstone-v2', '2026-09-08T00:00:00Z'
					)
					""".formatted(HISTORICAL_ASSESSMENT_ID, "a".repeat(64)));
			statement.executeUpdate("""
					insert into assessment_result (
					  submission_id, total_score, screening_level, scoring_version,
					  safety_item_positive, safety_status, safety_policy_version, calculated_at
					) values (
					  '%s', 4, 'MINIMAL', 'phq9-standard-bands-v1', false,
					  'NEGATIVE_SAFETY_SCREEN', 'MB-SAFETY-PHQ9-001/1.0-capstone',
					  '2026-09-08T00:00:00Z'
					)
					""".formatted(HISTORICAL_ASSESSMENT_ID));
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

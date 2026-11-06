package com.mentalbridge.care.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

@Testcontainers
class CareReassessmentSymptomChecklistMigrationTests {

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));

	@Test
	void upgradesFrom033WithoutRewritingHistoryAndEnforcesChecklistAndNativePlanEnvelopes() throws Exception {
		migrate(34);
		var owner = UUID.randomUUID();
		var report = UUID.randomUUID();
		var deleted = UUID.randomUUID();
		var summary = UUID.randomUUID();
		execute("insert into user_profile(account_id,display_name) values('%s','Synthetic upgrade user')".formatted(owner));
		execute(reportInsert(owner, report, false));
		execute(reportInsert(owner, deleted, true));
		execute("""
				insert into reassessment_summary(id,user_id,idempotency_key,request_hash,summary_version,journal_job_id,
				previous_period_start,previous_period_end,current_period_start,current_period_end,snapshot,composed_at)
				values('%s','%s','migration-summary-%s','%s','reassessment-summary-v2','%s',
				'2026-10-01T00:00:00Z','2026-10-15T00:00:00Z','2026-10-15T00:00:00Z','2026-10-29T00:00:00Z',
				'{"selfReportedExperience":{"state":"INSUFFICIENT_DATA","reasonCode":"NOT_PROVIDED"}}',now())
				""".formatted(summary, owner, summary, "b".repeat(64), UUID.randomUUID()));
		var nativePlan = insertPlan(owner, true);
		var legacyPlan = insertPlan(owner, false);
		var reportsBefore = scalar("select string_agg(to_jsonb(r)::text,'|' order by id) from reassessment_self_report r");
		var snapshotBefore = scalar("select row_to_json(s)::text from reassessment_summary s where id='%s'".formatted(summary));
		var plansBefore = scalar("select string_agg(to_jsonb(p)::text,'|' order by id) from support_plan p");
		var checksumsBefore = scalar("select string_agg(id || ':' || md5sum,',' order by orderexecuted) from databasechangelog");

		migrate(null);

		assertThat(scalar("select string_agg((to_jsonb(r)-'symptom_catalog_revision'-'symptom_codes')::text,'|' order by id) from reassessment_self_report r"))
				.isEqualTo(reportsBefore);
		assertThat(scalar("select row_to_json(s)::text from reassessment_summary s where id='%s'".formatted(summary)))
				.isEqualTo(snapshotBefore);
		assertThat(scalar("select string_agg(id || ':' || md5sum,',' order by orderexecuted) from databasechangelog where id <> 'care-035-reassessment-symptom-checklist'"))
				.isEqualTo(checksumsBefore);
		assertThat(scalar("select count(*) from reassessment_self_report where symptom_catalog_revision is null and cardinality(symptom_codes)=0")).isEqualTo("2");
		assertThat(scalar("select count(*) from support_plan where checklist_provenance is null")).isEqualTo("2");
		assertThat(scalar("select string_agg((to_jsonb(p)-'checklist_provenance')::text,'|' order by id) from support_plan p"))
				.isEqualTo(plansBefore);

		String update = "update reassessment_self_report set %s where id='%s'";
		for (String invalid : new String[] {
				"symptom_codes=ARRAY['LOW_ENERGY']",
				"symptom_catalog_revision=' '",
				"symptom_catalog_revision=E'\\t\\n'",
				"symptom_catalog_revision='reassessment-symptoms-v1',symptom_codes=ARRAY[NULL]::text[]",
				"symptom_catalog_revision='reassessment-symptoms-v1',symptom_codes=array_fill('LOW_ENERGY'::text,ARRAY[9])",
				"symptom_codes=NULL"
		}) {
			assertThatThrownBy(() -> execute(update.formatted(invalid, report))).isInstanceOf(SQLException.class);
		}
		execute(update.formatted("symptom_catalog_revision='reassessment-symptoms-v1',symptom_codes=ARRAY['LOW_ENERGY','LOW_MOOD']", report));
		assertThatThrownBy(() -> execute(update.formatted("deleted_at=now(),current_experience=NULL", report)))
				.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute(update.formatted("symptom_catalog_revision='reassessment-symptoms-v1'", deleted)))
				.isInstanceOf(SQLException.class);
		execute(update.formatted("symptom_catalog_revision='reassessment-symptoms-v1',symptom_codes='{}'", report));
		execute(update.formatted("symptom_catalog_revision=NULL,symptom_codes='{}',deleted_at=now(),current_experience=NULL,helpful_context=NULL,difficult_context=NULL", report));
		assertThat(scalar("select count(*) from reassessment_self_report where deleted_at is not null and symptom_catalog_revision is null and cardinality(symptom_codes)=0")).isEqualTo("2");

		assertThatThrownBy(() -> execute("update support_plan set checklist_provenance='[]' where id='%s'".formatted(nativePlan)))
				.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute("update support_plan set checklist_provenance='{}' where id='%s'".formatted(legacyPlan)))
				.isInstanceOf(SQLException.class);
		execute("update support_plan set checklist_provenance='{}' where id='%s'".formatted(nativePlan));
		migrate(null);
		assertThat(scalar("select count(*) from databasechangelog where id='care-035-reassessment-symptom-checklist'")).isEqualTo("1");
		assertThat(scalar("select row_to_json(s)::text from reassessment_summary s where id='%s'".formatted(summary)))
				.isEqualTo(snapshotBefore);
	}

	private void migrate(Integer count) throws Exception {
		try (var connection = connection(); var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
				new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
			if (count == null) liquibase.update(new Contexts(), new LabelExpression());
			else liquibase.update(count, new Contexts(), new LabelExpression());
		}
	}

	private String reportInsert(UUID owner, UUID id, boolean deleted) {
		return """
				insert into reassessment_self_report(id,user_id,idempotency_key,request_hash,source_version,
				current_period_start,current_period_end,current_experience,authored_at,updated_at,deleted_at)
				values('%s','%s','migration-report-%s','%s','reassessment-self-report-v1',
				'2026-10-15T00:00:00Z','2026-10-29T00:00:00Z',%s,now(),now(),%s)
				""".formatted(id, owner, id, "a".repeat(64), deleted ? "NULL" : "'UNSURE'", deleted ? "now()" : "NULL");
	}

	private UUID insertPlan(UUID owner, boolean nativePlan) throws SQLException {
		var phq = UUID.randomUUID();
		var gad = UUID.randomUUID();
		var evaluation = UUID.randomUUID();
		var plan = UUID.randomUUID();
		for (var assessment : new UUID[] {phq, gad}) {
			execute("""
					insert into assessment_submission(id,user_id,definition_id,idempotency_key,request_hash,privacy_policy_version,submitted_at)
					values('%s','%s','%s','migration-assessment-%s','%s','privacy-capstone-v3',now())
					""".formatted(assessment, owner, assessment.equals(phq)
							? "10000000-0000-0000-0000-000000000004" : "10000000-0000-0000-0000-000000000003", assessment, "0".repeat(64)));
		}
		execute("""
				insert into support_evaluation_v2(id,user_id,phq9_assessment_id,gad7_assessment_id,policy_version,evaluated_at)
				values('%s','%s','%s','%s','mb-support-routing-capstone-v2',now())
				""".formatted(evaluation, owner, phq, gad));
		execute("""
				insert into support_plan(id,user_id,support_evaluation_id,status,version,model_version,plan_policy_version,action_count,
				evaluation_policy_version,evaluated_at,selection_policy_version,resource_policy_version,resources_resolved_at,
				entitlement_package,entitlement_source,entitlement_policy_version,entitlement_version,entitlement_decided_at,
				rationale_code,rationale_text,safety_status,safety_reason_code,safety_policy_version,safety_guidance_code,safety_guidance,
				selected_resource_count,created_at,updated_at,discarded_at)
				values('%s','%s','%s','DISCARDED',0,%s,%s,%s,'mb-support-routing-capstone-v2',now(),%s,%s,%s,
				'PLUS','DEMO','service-entitlement-v1',1,now(),'SYNTHETIC','Synthetic rationale','NEGATIVE_SAFETY_SCREEN',
				'SYNTHETIC','MB-SAFETY-PHQ9-001-v1','SYNTHETIC','Synthetic guidance',%s,now(),now(),now())
				""".formatted(plan, owner, evaluation, nativePlan ? 3 : 2,
						nativePlan ? "'support-plan-journal-actions-v1'" : "NULL", nativePlan ? 1 : 0,
						nativePlan ? "NULL" : "'mb-support-plan-selection-v1'", nativePlan ? "NULL" : "'content-eligibility-v1'",
						nativePlan ? "NULL" : "now()", nativePlan ? "NULL" : "1"));
		return plan;
	}

	private void execute(String sql) throws SQLException {
		try (var connection = connection(); var statement = connection.createStatement()) { statement.executeUpdate(sql); }
	}

	private String scalar(String sql) throws SQLException {
		try (var connection = connection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			assertThat(result.next()).isTrue();
			return result.getString(1);
		}
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}
}

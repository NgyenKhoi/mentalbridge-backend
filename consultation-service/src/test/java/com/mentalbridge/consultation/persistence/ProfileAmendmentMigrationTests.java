package com.mentalbridge.consultation.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class ProfileAmendmentMigrationTests {

	@Test
	void upgradeBackfillsOnlyPublishedProfilesAndRetainsApprovalProvenance() throws Exception {
		try (var container = new PostgreSQLContainer(DockerImageName.parse("postgres:latest"))) {
			container.start();
			try (var connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
					var statement = connection.createStatement()) {
				var names = new String[] { "001-specialist-profile-approval", "002-current-service-entitlement",
						"003-online-specialist-availability", "004-service-plan-consultation-credits", "005-online-appointment-request",
						"006-specialist-lifecycle", "007-consultation-credit-policy-v2", "008-appointment-decisions", "009-appointment-changes",
						"010-chat-session-completion", "011-session-summary", "012-resource-proposal", "013-appointment-notification-outbox",
						"014-specialist-client-continuity", "015-appointment-rating", "016-appointment-disputes", "017-specialist-earning-payout" };
				for (var name : names) statement.execute(migration(name));
				var approver = UUID.randomUUID();
				var operator = UUID.randomUUID();
				for (var status : new String[] { "APPROVED", "SUSPENDED", "PENDING", "REJECTED" }) {
					var owner = UUID.randomUUID();
					var published = status.equals("APPROVED") || status.equals("SUSPENDED");
					statement.execute("""
							insert into specialist_profile (account_id, display_name, biography, years_experience, timezone,
							approval_status, submitted_at, reviewed_at, reviewed_by, decision_reason_code, created_at, updated_at)
							values ('%s', '%s', 'Existing biography', 5, 'Asia/Ho_Chi_Minh', '%s', %s, %s, %s, %s, now(), now())
							""".formatted(owner, status, status, status.equals("PENDING") ? "null" : "now()",
							status.equals("PENDING") ? "null" : "now()", status.equals("PENDING") ? "null" : "'" + operator + "'",
							status.equals("SUSPENDED") ? "'QUALITY_REVIEW_REQUIRED'" : status.equals("REJECTED") ? "'PROFILE_CONTENT_NOT_APPROVED'" : "null"));
					statement.execute("insert into specialist_profile_support_area values ('" + owner + "','ANXIETY_SYMPTOMS')");
					statement.execute("insert into specialist_profile_language values ('" + owner + "','vi')");
					if (published) statement.execute("""
							insert into specialist_profile_status_history (id, specialist_account_id, approval_status, actor_account_id, actor_role, occurred_at)
							values ('%s','%s','APPROVED','%s','ADMIN',now()-interval '1 day')
							""".formatted(UUID.randomUUID(), owner, approver));
				}
				statement.execute(migration("018-specialist-profile-amendment"));
				try (var rows = statement.executeQuery("select count(*), min(approved_by::text), max(approved_by::text) from specialist_profile_approved_version")) {
					rows.next(); assertThat(rows.getInt(1)).isEqualTo(2);
					assertThat(rows.getString(2)).isEqualTo(approver.toString());
					assertThat(rows.getString(3)).isEqualTo(approver.toString());
				}
				try (var rows = statement.executeQuery("select approval_status, published_version from specialist_profile order by approval_status")) {
					while (rows.next()) assertThat(rows.getLong(2)).isEqualTo(rows.getString(1).equals("APPROVED") || rows.getString(1).equals("SUSPENDED") ? 1 : 0);
				}
				try (var rows = statement.executeQuery("select profile_snapshot->>'displayName', jsonb_array_length(profile_snapshot->'languages') from specialist_profile_approved_version order by profile_snapshot->>'displayName'")) {
					rows.next(); assertThat(rows.getString(1)).isEqualTo("APPROVED"); assertThat(rows.getInt(2)).isEqualTo(1);
					rows.next(); assertThat(rows.getString(1)).isEqualTo("SUSPENDED"); assertThat(rows.getInt(2)).isEqualTo(1);
				}
				assertThatThrownBy(() -> statement.execute("""
						insert into specialist_profile_amendment (id, specialist_account_id, base_published_version, status,
						proposed_profile, submitted_at, reviewed_at, reviewed_by, reason_code, created_at, updated_at)
						select gen_random_uuid(), specialist_account_id, 1, 'REJECTED', profile_snapshot,
						now(), now(), approved_by, null, now(), now() from specialist_profile_approved_version limit 1
						""")).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("ck_amendment_state");
				statement.execute("""
						insert into specialist_profile_amendment (id, specialist_account_id, base_published_version, status, proposed_profile, created_at, updated_at)
						select gen_random_uuid(), specialist_account_id, 1, 'DRAFT', profile_snapshot, now(), now()
						from specialist_profile_approved_version limit 1
						""");
				statement.execute(migration("019-profile-amendment-cancellation"));
				statement.execute("update specialist_profile_amendment set status='CANCELLED'");
				statement.execute("""
						insert into specialist_profile_amendment_history (id, amendment_id, amendment_version, status, proposed_profile, actor_account_id, actor_role, occurred_at)
						select gen_random_uuid(), id, 0, 'CANCELLED', proposed_profile, specialist_account_id, 'SPECIALIST', now()
						from specialist_profile_amendment
						""");
				assertThatThrownBy(() -> statement.execute("update specialist_profile_amendment set submitted_at=now()"))
						.isInstanceOf(java.sql.SQLException.class).hasMessageContaining("ck_amendment_state");
				statement.execute("""
						insert into specialist_profile_amendment (id, specialist_account_id, base_published_version, status, proposed_profile, created_at, updated_at)
						select gen_random_uuid(), specialist_account_id, 1, 'DRAFT', proposed_profile, now(), now()
						from specialist_profile_amendment where status='CANCELLED'
						""");
			}
		}
	}

	private String migration(String name) throws Exception {
		try (var resource = getClass().getResourceAsStream("/db/changelog/changes/" + name + ".sql")) {
			return new String(java.util.Objects.requireNonNull(resource).readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}

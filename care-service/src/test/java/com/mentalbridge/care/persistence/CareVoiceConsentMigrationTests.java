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

import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

@Testcontainers
class CareVoiceConsentMigrationTests {
	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));

	@Test
	void upgradesExistingConsentHistoryWithoutRewritingRowsOrAppliedChecksums() throws Exception {
		migrate("db/changelog/changes/001-care-profile-consent.sql");
		var owner = UUID.randomUUID();
		execute("insert into user_profile(account_id,display_name) values('%s','Synthetic historical user')".formatted(owner));
		execute(insert(owner, "PRIVACY_POLICY", "privacy-capstone-v3", "synthetic-historical-key"));
		var checksum = scalar("select string_agg(id || ':' || md5sum,',' order by orderexecuted) from databasechangelog");
		var history = scalar("select row_to_json(c)::text from consent_decision c");
		assertThatThrownBy(() -> execute(insert(owner, "AI_VOICE_PROCESSING", "ai-voice-processing-capstone-v1", "synthetic-voice-before")))
				.isInstanceOf(SQLException.class);
		migrate("db/changelog/db.changelog-master.yaml");
		assertThat(scalar("select string_agg(id || ':' || md5sum,',' order by orderexecuted) from databasechangelog where filename like '%001-care-profile-consent.sql'"))
				.isEqualTo(checksum);
		assertThat(scalar("select row_to_json(c)::text from consent_decision c")).isEqualTo(history);
		execute(insert(owner, "AI_VOICE_PROCESSING", "ai-voice-processing-capstone-v1", "synthetic-voice-after"));
		assertThatThrownBy(() -> execute(insert(owner, "UNKNOWN_PURPOSE", "unavailable-v1", "synthetic-unknown-key")))
				.isInstanceOf(SQLException.class);
		var applied = scalar("select count(*) from databasechangelog");
		migrate("db/changelog/db.changelog-master.yaml");
		assertThat(scalar("select count(*) from databasechangelog")).isEqualTo(applied);
		assertThat(scalar("select count(*) from consent_decision")).isEqualTo("2");
	}

	private String insert(UUID owner, String purpose, String version, String key) {
		return """
				insert into consent_decision(user_id,consent_type,policy_version,granted,idempotency_key,request_hash,decided_at)
				values('%s','%s','%s',true,'%s','%s','2026-10-22T00:00:00Z')
				""".formatted(owner, purpose, version, key, "a".repeat(64));
	}

	private void migrate(String changelog) throws Exception {
		try (var connection = connection(); var liquibase = new Liquibase(changelog,
				new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) { liquibase.update(); }
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

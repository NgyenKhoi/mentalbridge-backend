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
class CareAiConsentLevelsMigrationTests {
	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));

	@Test
	void expandsOnlyContextTypeWithoutBackfillOrChangingHistoricalRowsAndChecksums() throws Exception {
		migrate("db/changelog/changes/001-care-profile-consent.sql");
		migrate("db/changelog/changes/032-ai-voice-processing-consent.sql");
		var owner = UUID.randomUUID();
		execute("insert into user_profile(account_id,display_name) values('%s','Synthetic upgrade user')".formatted(owner));
		execute(insert(owner, "AI_PROCESSING", "ai-processing-capstone-v1", "synthetic-existing-ai-v1"));
		execute(insert(owner, "AI_PROCESSING", "ai-processing-capstone-v2", "synthetic-existing-ai-v2"));
		execute(insert(owner, "AI_VOICE_PROCESSING", "ai-voice-processing-capstone-v2", "synthetic-existing-voice"));
		var history = scalar("select string_agg(row_to_json(c)::text,'|' order by id) from consent_decision c");
		var checksums = scalar("select string_agg(id || ':' || md5sum,',' order by orderexecuted) from databasechangelog");
		assertThatThrownBy(() -> execute(insert(owner, "AI_CONTEXT_PROCESSING", "ai-context-processing-capstone-v1", "synthetic-context-before")))
				.isInstanceOf(SQLException.class);
		migrate("db/changelog/changes/033-ai-consent-levels.sql");
		assertThat(scalar("select string_agg(row_to_json(c)::text,'|' order by id) from consent_decision c")).isEqualTo(history);
		assertThat(scalar("select string_agg(id || ':' || md5sum,',' order by orderexecuted) from databasechangelog where id <> 'care-034-ai-consent-levels'"))
				.isEqualTo(checksums);
		assertThat(scalar("select count(*) from consent_decision where consent_type='AI_CONTEXT_PROCESSING'")).isEqualTo("0");
		execute(insert(owner, "AI_CONTEXT_PROCESSING", "ai-context-processing-capstone-v1", "synthetic-context-after"));
		assertThatThrownBy(() -> execute(insert(owner, "AI_CHAT_PROCESSING", "forbidden-name-v1", "synthetic-forbidden-chat")))
				.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute(insert(owner, "UNKNOWN_PURPOSE", "unknown-v1", "synthetic-unknown-purpose")))
				.isInstanceOf(SQLException.class);
		migrate("db/changelog/changes/033-ai-consent-levels.sql");
		assertThat(scalar("select count(*) from databasechangelog where id='care-034-ai-consent-levels'")).isEqualTo("1");
		assertThat(scalar("select count(*) from consent_decision")).isEqualTo("4");
	}

	private String insert(UUID owner, String purpose, String version, String key) {
		return """
				insert into consent_decision(user_id,consent_type,policy_version,granted,idempotency_key,request_hash,decided_at)
				values('%s','%s','%s',true,'%s','%s','2026-11-06T00:00:00Z')
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

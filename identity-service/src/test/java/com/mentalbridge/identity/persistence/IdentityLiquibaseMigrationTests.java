package com.mentalbridge.identity.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.identity.TestcontainersConfiguration;
import com.mentalbridge.identity.IdentityTestProperties;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class IdentityLiquibaseMigrationTests extends IdentityTestProperties {

	@Autowired
	private JdbcClient jdbc;

	@Test
	void migrationCreatesTablesInTheDedicatedDatabaseDefaultSchemaAndSeedsRoles() {
		var tables = jdbc.sql("""
				select table_name
				from information_schema.tables
				where table_schema = 'public'
				""").query(String.class).list();
		var identitySchemaCount = jdbc.sql("""
				select count(*)
				from information_schema.schemata
				where schema_name = 'identity'
				""").query(Long.class).single();
		var roles = jdbc.sql("select code from role order by code").query(String.class).list();

		assertThat(tables).contains("account", "account_role", "refresh_session", "one_time_token",
				"idempotency_record", "outbox_event", "security_audit_event");
		assertThat(identitySchemaCount).isZero();
		assertThat(roles).containsExactly("ADMIN", "SPECIALIST", "USER");
	}

	@Test
	void normalizedEmailUniquenessIsCaseInsensitive() {
		insertPendingAccount("person@example.com");

		assertThatThrownBy(() -> insertPendingAccount("PERSON@example.com"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void onlyOneActiveChallengeExistsForAnAccountAndPurpose() {
		var accountId = insertPendingAccount("challenge@example.com");
		insertChallenge(accountId, "a".repeat(64));

		assertThatThrownBy(() -> insertChallenge(accountId, "b".repeat(64)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private UUID insertPendingAccount(String email) {
		return jdbc.sql("""
				insert into account (email, password_hash)
				values (:email, :passwordHash)
				returning id
				""").param("email", email).param("passwordHash", "bcrypt-test-hash").query(UUID.class).single();
	}

	private void insertChallenge(UUID accountId, String tokenHash) {
		jdbc.sql("""
				insert into one_time_token (account_id, purpose, token_hash, expires_at)
				values (:accountId, 'VERIFY_EMAIL', :tokenHash, now() + interval '1 hour')
				""").param("accountId", accountId).param("tokenHash", tokenHash).update();
	}

}

package com.mentalbridge.identity.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
class IdentityAdminSeedMigrationTests {

	private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));

	@BeforeEach
	void migrateWithoutSeed() throws Exception {
		try (var connection = connection(); var statement = connection.createStatement()) {
			statement.execute("drop schema public cascade");
			statement.execute("create schema public");
		}
		migrate(new Contexts("test"));
	}

	@Test
	void testContextSkipsSeedAndNoContextMigrationCreatesOneVerifiedAdministrator() throws Exception {
		assertThat(adminCount()).isZero();
		assertThat(pendingChangesets()).containsExactly("identity-011-staging-admin-seed");

		migrate(new Contexts());

		assertThat(adminCount()).isEqualTo(1);
		var seeded = admin();
		assertThat(seeded.email()).isEqualTo("admin@mentalbridge.online");
		assertThat(seeded.status()).isEqualTo("ACTIVE");
		assertThat(seeded.emailVerified()).isTrue();
		assertThat(seeded.passwordHash()).startsWith("$2a$12$");
		assertThat(seeded.passwordHash())
				.isEqualTo("$2a$12$fHs05rbKpwj9BU1iWZ6.ZudQHS.UbJPIy9ZVME4mUauGfIYUZX.G2");
		assertThat(pendingChangesets()).isEmpty();

		migrate(new Contexts());

		assertThat(adminCount()).isEqualTo(1);
		assertThat(admin()).isEqualTo(seeded);
	}

	@Test
	void noContextMigrationPreservesAnExistingAdministratorWithTheSeedEmail() throws Exception {
		var existingId = UUID.randomUUID();
		try (var connection = connection(); var statement = connection.prepareStatement("""
				insert into account (id, email, password_hash, role_code, status)
				values (?, 'admin@mentalbridge.online', 'existing-admin-hash', 'ADMIN', 'DISABLED')
				""")) {
			statement.setObject(1, existingId);
			statement.executeUpdate();
		}
		var existing = admin();

		migrate(new Contexts());

		assertThat(adminCount()).isEqualTo(1);
		assertThat(admin()).isEqualTo(existing);
		assertThat(admin().id()).isEqualTo(existingId);
		assertThat(pendingChangesets()).isEmpty();
	}

	private void migrate(Contexts contexts) throws Exception {
		try (var connection = connection(); var resources = new ClassLoaderResourceAccessor();
				var liquibase = new Liquibase(CHANGELOG, resources, new JdbcConnection(connection))) {
			liquibase.update(contexts, new LabelExpression());
		}
	}

	private List<String> pendingChangesets() throws Exception {
		try (var connection = connection(); var resources = new ClassLoaderResourceAccessor();
				var liquibase = new Liquibase(CHANGELOG, resources, new JdbcConnection(connection))) {
			return liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression()).stream()
					.map(changeSet -> changeSet.getId()).toList();
		}
	}

	private long adminCount() throws SQLException {
		try (var connection = connection(); var statement = connection.createStatement();
				var result = statement.executeQuery("select count(*) from account where role_code = 'ADMIN'")) {
			assertThat(result.next()).isTrue();
			return result.getLong(1);
		}
	}

	private Admin admin() throws SQLException {
		try (var connection = connection(); var statement = connection.createStatement();
				var result = statement.executeQuery("""
						select id, email, password_hash, status, email_verified_at is not null
						from account where role_code = 'ADMIN'
						""")) {
			assertThat(result.next()).isTrue();
			var admin = new Admin(result.getObject(1, UUID.class), result.getString(2), result.getString(3),
					result.getString(4), result.getBoolean(5));
			assertThat(result.next()).isFalse();
			return admin;
		}
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private record Admin(UUID id, String email, String passwordHash, String status, boolean emailVerified) {
	}

}

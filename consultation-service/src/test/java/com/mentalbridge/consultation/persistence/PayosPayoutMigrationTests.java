package com.mentalbridge.consultation.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PayosPayoutMigrationTests extends ConsultationTestProperties {

	@Autowired JdbcClient jdbc;
	@Autowired DataSource dataSource;

	@Test
	void upgradesOldMomoDestinationAndAttemptWithoutChangingHistoricalProviderReferencesOrSnapshots() throws Exception {
		var schema = "payout_upgrade_" + UUID.randomUUID().toString().replace("-", "");
		jdbc.sql("create schema " + schema).update();
		try (var connection = dataSource.getConnection(); var resources = new ClassLoaderResourceAccessor()) {
			try (var statement = connection.createStatement()) {
				statement.execute("set search_path to " + schema + ", public");
			}
			var isolatedDataSource = new SingleConnectionDataSource(connection, true);
			var database = DatabaseFactory.getInstance()
					.findCorrectDatabaseImplementation(new JdbcConnection(isolatedDataSource.getConnection()));
			database.setDefaultSchemaName(schema);
			database.setLiquibaseSchemaName(schema);
			try (var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", resources, database)) {
				liquibase.update(ConsultationLiquibaseChangelogTests.legacyHistory().size() + 2,
						new Contexts(), new LabelExpression());
				var isolated = JdbcClient.create(isolatedDataSource);
				var owner = specialist(isolated);
				var destination = legacyDestination(isolated, owner);
				var payout = payout(isolated, owner, destination, "MOMO");
				var attempt = attempt(isolated, payout, "FAKE", payout + ":1");
				var beforeDestination = isolated.sql("select * from specialist_payout_destination where id=:id")
						.param("id", destination).query().singleRow();
				var beforeAttempt = isolated.sql("select * from specialist_payout_attempt where id=:id")
						.param("id", attempt).query().singleRow();
				liquibase.update(new Contexts(), new LabelExpression());
				assertThat(isolated.sql("select * from specialist_payout_destination where id=:id")
						.param("id", destination).query().singleRow()).containsAllEntriesOf(beforeDestination)
						.containsEntry("is_current", true).containsEntry("confirmed_by", null);
				assertThat(isolated.sql("select * from specialist_payout_attempt where id=:id")
						.param("id", attempt).query().singleRow()).containsAllEntriesOf(beforeAttempt)
						.containsEntry("provider_transfer_reference", null);
				var applied = isolated.sql("select * from databasechangelog order by orderexecuted").query().listOfRows();
				liquibase.validate();
				liquibase.update(new Contexts(), new LabelExpression());
				assertThat(isolated.sql("select * from databasechangelog order by orderexecuted").query().listOfRows())
						.isEqualTo(applied);
			}
			finally { connection.setSchema("public"); }
		}
		finally { jdbc.sql("drop schema " + schema + " cascade").update(); }
	}

	@Test
	void databaseEnforcesPendingVerificationCurrentRevisionProviderAffinityAndPayosUuidKey() {
		var owner = specialist(jdbc);
		var destination = legacyDestination(jdbc, owner);
		assertThat(jdbc.sql("update specialist_payout_destination set is_current=true where id=:id")
				.param("id", destination).update()).isOne();
		assertThatThrownBy(() -> jdbc.sql("""
				update specialist_payout_destination set status='PENDING_CONFIRMATION' where id=:id
				""").param("id", destination).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("""
				update specialist_payout_destination set payout_provider='PAYOS' where id=:id
				""").param("id", destination).update()).isInstanceOf(DataIntegrityViolationException.class);
		var pending = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_payout_destination (id,specialist_account_id,payout_provider,destination_type,
				 destination_ciphertext,encryption_key_version,destination_fingerprint,display_hint,status,created_at,updated_at)
				values (:id,:owner,'PAYOS','BANK_ACCOUNT','synthetic-ciphertext','test-v1',:fingerprint,'****7890',
				 'PENDING_CONFIRMATION',now(),now())
				""").param("id", pending).param("owner", owner).param("fingerprint", "a".repeat(64)).update();
		assertThatThrownBy(() -> jdbc.sql("update specialist_payout_destination set is_current=true where id=:id")
				.param("id", pending).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("""
				update specialist_payout_destination set status='VERIFIED',verified_at=now() where id=:id
				""").param("id", pending).update()).isInstanceOf(DataIntegrityViolationException.class);
		var payout = payout(jdbc, owner, destination, "MOMO");
		assertThatThrownBy(() -> attempt(jdbc, payout, "PAYOS", UUID.randomUUID().toString()))
				.isInstanceOf(DataIntegrityViolationException.class);
		var payosOwner = specialist(jdbc);
		var payosDestination = legacyDestination(jdbc, payosOwner);
		var payosPayout = payout(jdbc, payosOwner, payosDestination, "PAYOS");
		assertThatThrownBy(() -> attempt(jdbc, payosPayout, "PAYOS", payosPayout + ":1"))
				.isInstanceOf(DataIntegrityViolationException.class);
		var valid = attempt(jdbc, payosPayout, "PAYOS", UUID.randomUUID().toString());
		assertThatThrownBy(() -> jdbc.sql("""
				update specialist_payout_attempt set reconciliation_started_at=now(),
				 reconciliation_deadline_at=now()+interval '25 hours' where id=:id
				""").param("id", valid).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("""
				update specialist_payout_attempt set reconciliation_started_at=now(),
				 reconciliation_deadline_at=null where id=:id
				""").param("id", valid).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("""
				update specialist_payout_attempt set reconciliation_started_at=null,
				 reconciliation_deadline_at=now()+interval '24 hours' where id=:id
				""").param("id", valid).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void retryCommandsKeepBothAttemptsInTheOriginalPayoutAndDeduplicateTheAdminKey() {
		var owner = specialist(jdbc);
		var destination = legacyDestination(jdbc, owner);
		var payout = payout(jdbc, owner, destination, "MOMO");
		var first = attempt(jdbc, payout, "MOMO", payout + ":1");
		var second = attempt(jdbc, payout, "MOMO", payout + ":2", 2);
		var admin = UUID.randomUUID();
		retryCommand(admin, "synthetic-retry-command-01", payout, first, second);
		assertThatThrownBy(() -> retryCommand(admin, "synthetic-retry-command-01", payout, first, second))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> retryCommand(admin, "synthetic-retry-command-02", payout, first, first))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> retryCommand(admin, "short", payout, first, second))
				.isInstanceOf(DataIntegrityViolationException.class);
		var otherOwner = specialist(jdbc);
		var otherPayout = payout(jdbc, otherOwner, legacyDestination(jdbc, otherOwner), "MOMO");
		var foreignAttempt = attempt(jdbc, otherPayout, "MOMO", otherPayout + ":1");
		assertThatThrownBy(() -> retryCommand(admin, "synthetic-retry-command-03", payout, first, foreignAttempt))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> retryCommand(admin, "synthetic-retry-command-04", payout, foreignAttempt, second))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void confirmationCommandsRequireTheCheckedVersionTransitionAndAnAdminScopedKey() {
		var destination = legacyDestination(jdbc, specialist(jdbc));
		var admin = UUID.randomUUID();
		confirmationCommand(admin, "synthetic-confirm-command-01", destination, 0, 1);
		assertThatThrownBy(() -> confirmationCommand(admin, "synthetic-confirm-command-01", destination, 0, 1))
				.isInstanceOf(DataIntegrityViolationException.class);
		confirmationCommand(UUID.randomUUID(), "synthetic-confirm-command-01", destination, 0, 1);
		assertThatThrownBy(() -> confirmationCommand(admin, "synthetic-confirm-command-02", destination, -1, 0))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> confirmationCommand(admin, "synthetic-confirm-command-03", destination, 0, 2))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> confirmationCommand(admin, "short", destination, 0, 1))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void onlyFailedPayoutsCanCarryPairedClosureTimeAndActor() {
		var owner = specialist(jdbc);
		var payout = payout(jdbc, owner, legacyDestination(jdbc, owner), "MOMO");
		var admin = UUID.randomUUID();
		assertThatThrownBy(() -> jdbc.sql("update specialist_payout set closed_at=now(),closed_by=:admin where id=:id")
				.param("admin", admin).param("id", payout).update()).isInstanceOf(DataIntegrityViolationException.class);
		jdbc.sql("update specialist_payout set status='FAILED' where id=:id").param("id", payout).update();
		assertThatThrownBy(() -> jdbc.sql("update specialist_payout set closed_at=now() where id=:id")
				.param("id", payout).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("update specialist_payout set closed_by=:admin where id=:id")
				.param("admin", admin).param("id", payout).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(jdbc.sql("update specialist_payout set closed_at=now(),closed_by=:admin where id=:id")
				.param("admin", admin).param("id", payout).update()).isOne();
		assertThatThrownBy(() -> jdbc.sql("update specialist_payout set status='PROCESSING' where id=:id")
				.param("id", payout).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void closeCommandsRequireAnAttemptFromTheSamePayoutAndDeduplicateTheAdminKey() {
		var owner = specialist(jdbc);
		var payout = payout(jdbc, owner, legacyDestination(jdbc, owner), "MOMO");
		var expected = attempt(jdbc, payout, "MOMO", payout + ":1");
		var admin = UUID.randomUUID();
		closeCommand(admin, "synthetic-close-command-01", payout, expected, "DESTINATION_UNUSABLE");
		assertThatThrownBy(() -> closeCommand(admin, "synthetic-close-command-01", payout, expected, "DESTINATION_UNUSABLE"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> closeCommand(admin, "synthetic-close-command-02", payout, expected, "UNVERIFIED_REASON"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> closeCommand(admin, "short", payout, expected, "DESTINATION_UNUSABLE"))
				.isInstanceOf(DataIntegrityViolationException.class);
		var otherOwner = specialist(jdbc);
		var otherPayout = payout(jdbc, otherOwner, legacyDestination(jdbc, otherOwner), "MOMO");
		var foreignAttempt = attempt(jdbc, otherPayout, "MOMO", otherPayout + ":1");
		assertThatThrownBy(() -> closeCommand(admin, "synthetic-close-command-03", payout, foreignAttempt, "DESTINATION_UNUSABLE"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void closeCommand(UUID admin, String key, UUID payout, UUID expected, String reason) {
		jdbc.sql("""
				insert into payout_close_command
				 (id,actor_account_id,idempotency_key,payout_id,expected_attempt_id,reason_code,created_at)
				values (:id,:admin,:key,:payout,:expected,:reason,now())
				""").param("id", UUID.randomUUID()).param("admin", admin).param("key", key)
				.param("payout", payout).param("expected", expected).param("reason", reason).update();
	}

	private void confirmationCommand(UUID admin, String key, UUID destination, long expected, long result) {
		jdbc.sql("""
				insert into payout_destination_confirmation_command
				 (id,actor_account_id,idempotency_key,destination_id,expected_version,reason_code,result_version,created_at)
				values (:id,:admin,:key,:destination,:expected,'ACCOUNT_HOLDER_CHECKED',:result,now())
				""").param("id", UUID.randomUUID()).param("admin", admin).param("key", key)
				.param("destination", destination).param("expected", expected).param("result", result).update();
	}

	private void retryCommand(UUID admin, String key, UUID payout, UUID expected, UUID result) {
		jdbc.sql("""
				insert into payout_retry_command
				 (id,actor_account_id,idempotency_key,payout_id,expected_attempt_id,result_attempt_id,created_at)
				values (:id,:admin,:key,:payout,:expected,:result,now())
				""").param("id", UUID.randomUUID()).param("admin", admin).param("key", key)
				.param("payout", payout).param("expected", expected).param("result", result).update();
	}

	private UUID specialist(JdbcClient target) {
		var id = UUID.randomUUID();
		target.sql("""
				insert into specialist_profile (account_id,display_name,biography,years_experience,timezone,
				 approval_status,submitted_at,reviewed_at,reviewed_by)
				values (:id,'Synthetic payout specialist','Synthetic profile',5,'UTC','APPROVED',now(),now(),:admin)
				""").param("id", id).param("admin", UUID.randomUUID()).update();
		return id;
	}

	private UUID legacyDestination(JdbcClient target, UUID owner) {
		var id = UUID.randomUUID();
		target.sql("""
				insert into specialist_payout_destination (id,specialist_account_id,payout_provider,destination_type,
				 destination_ciphertext,encryption_key_version,destination_fingerprint,display_hint,status,
				 verified_at,created_at,updated_at)
				values (:id,:owner,'MOMO','MOMO_WALLET','synthetic-ciphertext','test-v1',:fingerprint,
				 '****7890','VERIFIED',now(),now(),now())
				""").param("id", id).param("owner", owner).param("fingerprint", "a".repeat(64)).update();
		return id;
	}

	private UUID payout(JdbcClient target, UUID owner, UUID destination, String provider) {
		var id = UUID.randomUUID();
		target.sql("""
				insert into specialist_payout (id,specialist_account_id,destination_id,currency,amount_minor,payout_provider,
				 status,idempotency_key,requested_on,requested_at,created_at,updated_at)
				values (:id,:owner,:destination,'VND',210000,:provider,'PROCESSING',:key,current_date,now(),now(),now())
				""").param("id", id).param("owner", owner).param("destination", destination)
				.param("provider", provider).param("key", "synthetic-payout-" + id).update();
		return id;
	}

	private UUID attempt(JdbcClient target, UUID payout, String provider, String key) {
		return attempt(target, payout, provider, key, 1);
	}

	private UUID attempt(JdbcClient target, UUID payout, String provider, String key, int number) {
		var id = UUID.randomUUID();
		target.sql("""
				insert into specialist_payout_attempt (id,payout_id,payout_provider,attempt_number,provider_idempotency_key,
				 provider_payout_reference,status,requested_at,created_at,updated_at)
				values (:id,:payout,:provider,:number,:key,:reference,'PROCESSING',now(),now(),now())
				""").param("id", id).param("payout", payout).param("provider", provider)
				.param("key", key).param("number", number).param("reference", "synthetic-provider-" + id).update();
		return id;
	}
}

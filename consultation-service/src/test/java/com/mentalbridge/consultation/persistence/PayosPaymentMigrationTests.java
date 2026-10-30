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
class PayosPaymentMigrationTests extends ConsultationTestProperties {

	private static final UUID PLUS = UUID.fromString("62200000-0000-4000-8000-000000000002");
	private static final UUID PREMIUM = UUID.fromString("62200000-0000-4000-8000-000000000003");
	@Autowired JdbcClient jdbc;
	@Autowired DataSource dataSource;

	@Test
	void additiveUpgradePreservesLegacyMomoRowsAndCreditHistoryWithoutReplayingHistory() throws Exception {
		var schema = "payos_upgrade_" + UUID.randomUUID().toString().replace("-", "");
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
				liquibase.update(ConsultationLiquibaseChangelogTests.legacyHistory().size(), new Contexts(), new LabelExpression());
				var isolated = JdbcClient.create(isolatedDataSource);
				var subscription = insertSubscription(isolated);
				var legacyPayment = insertPayment(isolated, subscription, "MOMO", null, null, null);
				var before = isolated.sql("select * from payment_transaction where id=:id").param("id", legacyPayment)
						.query().singleRow();
				var credit = insertCredit(isolated);
				var expiredUpgrade = insertUpgrade(isolated, subscription, legacyPayment, "EXPIRED");
				insertUpgradeCredit(isolated, expiredUpgrade, credit);
				var snapshot = isolated.sql("select * from subscription_upgrade_credit where upgrade_id=:id")
						.param("id", expiredUpgrade).query().singleRow();
				assertThat(isolated.sql("""
						select count(*) from pg_constraint where conrelid='subscription_upgrade_credit'::regclass
						and conname='subscription_upgrade_credit_credit_id_key' and contype='u'
						""").query(Long.class).single()).isOne();
				liquibase.update(new Contexts(), new LabelExpression());
				assertThat(isolated.sql("select * from payment_transaction where id=:id").param("id", legacyPayment)
						.query().singleRow()).containsAllEntriesOf(before)
						.containsEntry("terminal_reason", null).containsEntry("order_code", null);
				assertThat(isolated.sql("select * from subscription_upgrade_credit where upgrade_id=:id")
						.param("id", expiredUpgrade).query().singleRow()).isEqualTo(snapshot);
				var retryPayment = insertPayment(isolated, subscription, "MOMO", null, null, null);
				var retryUpgrade = insertUpgrade(isolated, subscription, retryPayment, "PENDING_PAYMENT");
				insertUpgradeCredit(isolated, retryUpgrade, credit);
				assertThat(isolated.sql("select count(*) from subscription_upgrade_credit where credit_id=:id")
						.param("id", credit).query(Long.class).single()).isEqualTo(2);
				var duplicateSnapshot = connection.setSavepoint();
				try {
					assertThatThrownBy(() -> insertUpgradeCredit(isolated, retryUpgrade, credit))
							.isInstanceOf(DataIntegrityViolationException.class);
				}
				finally {
					connection.rollback(duplicateSnapshot);
					connection.releaseSavepoint(duplicateSnapshot);
				}
				var competingPayment = insertPayment(isolated, subscription, "MOMO", null, null, null);
				var competingUpgrade = connection.setSavepoint();
				try {
					assertThatThrownBy(() -> insertUpgrade(isolated, subscription, competingPayment, "PENDING_PAYMENT"))
							.isInstanceOf(DataIntegrityViolationException.class);
				}
				finally {
					connection.rollback(competingUpgrade);
					connection.releaseSavepoint(competingUpgrade);
				}
				var history = isolated.sql("select * from databasechangelog order by orderexecuted").query().listOfRows();
				liquibase.validate();
				liquibase.update(new Contexts(), new LabelExpression());
				assertThat(isolated.sql("select * from databasechangelog order by orderexecuted").query().listOfRows())
						.isEqualTo(history);
				assertThat(insertPayment(isolated, subscription, "PAYOS", 11L, "upgrade-link", null)).isNotNull();
			}
			finally {
				connection.setSchema("public");
			}
		}
		finally {
			jdbc.sql("drop schema " + schema + " cascade").update();
		}
	}

	private UUID insertCredit(JdbcClient target) {
		var period = UUID.randomUUID();
		target.sql("""
				insert into service_credit_period (id,account_id,plan_version,credit_policy_version,package_code,
				 source,source_reference,period_start,period_end,allocated_count,created_at,updated_at)
				values (:id,:user,'migration-policy','consultation-credit-v1','PLUS','DEMO',:source,
				 now()-interval '1 day',now()+interval '29 days',1,now(),now())
				""").param("id", period).param("user", UUID.randomUUID()).param("source", "migration-credit-" + period).update();
		var credit = UUID.randomUUID();
		target.sql("""
				insert into service_credit (id,period_id,ordinal,state,created_at,updated_at)
				values (:id,:period,1,'AVAILABLE',now(),now())
				""").param("id", credit).param("period", period).update();
		return credit;
	}

	private UUID insertUpgrade(JdbcClient target, UUID subscription, UUID payment, String status) {
		var id = UUID.randomUUID();
		target.sql("""
				insert into subscription_upgrade (id,subscription_id,from_plan_version_id,to_plan_version_id,
				 payment_id,old_period_start,old_period_end,total_period_seconds,remaining_period_seconds,
				 remaining_feature_value_minor,available_credit_value_minor,offset_minor,amount_due_minor,
				 currency,status,idempotency_key,quoted_at,quote_expires_at,created_at,updated_at,pricing_policy_version)
				values (:id,:subscription,:fromPlan,:toPlan,:payment,now()-interval '1 day',now()+interval '29 days',
				 2592000,2500000,0,300000,0,5000,'VND',:status,:key,now(),now()+interval '15 minutes',now(),now(),
				 'sandbox-full-price-v1')
				""").param("id", id).param("subscription", subscription).param("fromPlan", PLUS).param("toPlan", PREMIUM)
				.param("payment", payment).param("status", status).param("key", "migration-upgrade-" + id).update();
		return id;
	}

	private void insertUpgradeCredit(JdbcClient target, UUID upgrade, UUID credit) {
		target.sql("""
				insert into subscription_upgrade_credit (upgrade_id,credit_id,allocated_value_minor)
				values (:upgrade,:credit,300000)
				""").param("upgrade", upgrade).param("credit", credit).update();
	}

	@Test
	void payosIdentityAndTerminalReasonsAreConstrainedWhileLegacyRowsRemainValid() {
		var subscription = insertSubscription(jdbc);
		var legacy = insertPayment(jdbc, subscription, "MOMO", null, null, null);
		assertThat(jdbc.sql("select terminal_reason from payment_transaction where id=:id").param("id", legacy)
				.query().singleRow()).containsEntry("terminal_reason", null);
		var firstOrder = jdbc.sql("select nextval('payment_order_code_seq')").query(Long.class).single();
		var nextOrder = jdbc.sql("select nextval('payment_order_code_seq')").query(Long.class).single();
		assertThat(nextOrder).isGreaterThan(firstOrder).isLessThanOrEqualTo(9007199254740991L);
		assertThat(jdbc.sql("select max_value from pg_sequences where schemaname='public' and sequencename='payment_order_code_seq'")
				.query(Long.class).single()).isEqualTo(9007199254740991L);
		assertThat(jdbc.sql("select cycle from pg_sequences where schemaname='public' and sequencename='payment_order_code_seq'")
				.query(Boolean.class).single()).isFalse();
		var payment = insertPayment(jdbc, subscription, "PAYOS", firstOrder, "link-" + UUID.randomUUID(), "ref-" + UUID.randomUUID());
		assertThatThrownBy(() -> insertPayment(jdbc, subscription, "PAYOS", firstOrder, null, null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertPayment(jdbc, subscription, "PAYOS", null, null, null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertPayment(jdbc, subscription, "PAYOS", 9007199254740992L, null, null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("update payment_transaction set terminal_reason='CANCELLED' where id=:id")
				.param("id", payment).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("update payment_transaction set momo_order_id=null where id=:id")
				.param("id", legacy).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(jdbc.sql("update payment_transaction set status='FAILED',failed_at=now(),terminal_reason='CANCELLED' where id=:id")
				.param("id", payment).update()).isOne();
		assertThatThrownBy(() -> jdbc.sql("update payment_transaction set terminal_reason='URL_CANCELLED' where id=:id")
				.param("id", payment).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void payosLinkAndSettledReferenceCannotBeReusedByAnotherPayment() {
		var subscription = insertSubscription(jdbc);
		var link = "link-" + UUID.randomUUID();
		var reference = "reference-" + UUID.randomUUID();
		var legacy = insertPayment(jdbc, subscription, "MOMO", null, null, null);
		insertPayment(jdbc, subscription, "PAYOS", nextOrder(), link, reference);
		assertThatThrownBy(() -> insertPayment(jdbc, subscription, "PAYOS", nextOrder(), link, null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertPayment(jdbc, subscription, "PAYOS", nextOrder(), null, reference))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("update payment_transaction set payos_payment_link_id=:link where id=:id")
				.param("link", "invalid-" + UUID.randomUUID()).param("id", legacy).update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void verifiedReceiptDedupeAllowsDistinctReusedReferenceEvidence() {
		var reference = "ref-" + UUID.randomUUID();
		var dedupe = UUID.randomUUID().toString().replace("-", "").repeat(2);
		insertReceipt(dedupe, reference);
		assertThatThrownBy(() -> insertReceipt(dedupe, reference)).isInstanceOf(DataIntegrityViolationException.class);
		insertReceipt(UUID.randomUUID().toString().replace("-", "").repeat(2), reference);
		assertThat(jdbc.sql("select count(*) from payos_payment_webhook where reference=:ref")
				.param("ref", reference).query(Integer.class).single()).isEqualTo(2);
	}

	private long nextOrder() {
		return jdbc.sql("select nextval('payment_order_code_seq')").query(Long.class).single();
	}

	private void insertReceipt(String dedupe, String reference) {
		jdbc.sql("""
				insert into payos_payment_webhook (id,contract_version,source,deduplication_key,order_code,amount_minor,
				 payment_link_id,reference,payload_sha256,signature_key_version,signature_verified_at,
				 processing_status,failure_code,received_at)
				values (:id,'payos-v2','WEBHOOK',:dedupe,123,5000,'sample-link',:ref,:hash,'test-v1',now(),
				 'VERIFIED_UNMATCHED','PAYMENT_NOT_FOUND',now())
				""").param("id", UUID.randomUUID()).param("dedupe", dedupe).param("ref", reference)
				.param("hash", "a".repeat(64)).update();
	}

	private UUID insertSubscription(JdbcClient target) {
		var id = UUID.randomUUID();
		target.sql("""
				insert into user_subscription (id,user_id,plan_version_id,status,idempotency_key,created_at,updated_at)
				values (:id,:user,:plan,'PENDING_PAYMENT',:key,now(),now())
				""").param("id", id).param("user", UUID.randomUUID()).param("plan", PLUS).param("key", "migration-" + id).update();
		return id;
	}

	private UUID insertPayment(JdbcClient target, UUID subscription, String provider, Long orderCode, String link, String reference) {
		var id = UUID.randomUUID();
		var payos = "PAYOS".equals(provider);
		target.sql("""
				insert into payment_transaction (id,subscription_id,target_plan_version_id,payment_provider,
				 momo_order_id,momo_request_id,amount_minor,currency,status,purpose,idempotency_key,
				 expires_at,created_at,updated_at%s)
				values (:id,:subscription,:plan,:provider,:orderId,:requestId,5000,'VND','PENDING','PURCHASE',:key,
				 now()+interval '15 minutes',now(),now()%s)
				""".formatted(payos ? ",order_code,payos_payment_link_id,payos_reference" : "",
						payos ? ",:orderCode,:link,:reference" : ""))
				.param("id", id).param("subscription", subscription).param("plan", PLUS).param("provider", provider)
				.param("orderId", payos ? null : "order-" + id).param("requestId", payos ? null : "request-" + id)
				.param("key", "migration-payment-" + id)
				.param("orderCode", orderCode).param("link", link).param("reference", reference).update();
		return id;
	}
}

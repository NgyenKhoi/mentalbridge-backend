package com.mentalbridge.consultation.earnings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.mentalbridge.consultation.dispute.AppointmentSettlementGate;
import com.mentalbridge.consultation.shared.ApiException;
import com.mentalbridge.consultation.specialist.ConsultationAdminAuditOutbox;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties="mentalbridge.consultation.payout.reconciliation-interval=PT24H")
class StagingDemoEarningIntegrationTests extends ConsultationTestProperties {

	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired AppointmentSettlementGate settlementGate;
	@Autowired ConsultationAdminAuditOutbox audit;
	@Autowired PayoutProperties applicationProperties;
	@org.springframework.test.context.bean.override.mockito.MockitoBean PayoutReconciliationScheduler scheduler;
	private PayoutProperties properties;
	private Clock clock;
	private SpecialistEarningService earnings;
	private PayoutTransactions transactions;
	private PayoutDestinationService destinations;
	private SpecialistPayoutService payouts;
	private PayoutProvider provider;

	@BeforeEach
	void stagingDemo() {
		properties = new PayoutProperties();
		properties.setMode("PAYOS");
		properties.setStagingDemoEnabled(true);
		properties.setMinimumWithdrawalVnd(2_000);
		properties.setSettlementHold(Duration.ofMinutes(10));
		properties.setEncryptionKey(applicationProperties.getEncryptionKey());
		properties.setPayosClientId("synthetic-client");
		properties.setPayosApiKey("synthetic-api");
		properties.setPayosChecksumKey("synthetic-checksum");
		setProfiles("staging");
		provider = mock(PayoutProvider.class);
		when(provider.submit(eq("PAYOS"), any(PayoutProvider.Command.class))).thenReturn(new PayoutProvider.Result(
				"SUCCEEDED", "synthetic-batch-" + UUID.randomUUID(), null, "synthetic-child-" + UUID.randomUUID()));
		installClock(Clock.fixed(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC));
	}

	@Test
	void verifiedPlusFundingSurvivesUpgradeAndCreatesOne875EarningPerConsumedCredit() {
		var purchase = purchase("PLUS", 5_000, "PAYOS", true);
		jdbc.sql("update payos_payment_webhook set source='RECONCILIATION' where payment_id=:id")
				.param("id", purchase.paymentId()).update();
		jdbc.sql("update user_subscription set plan_version_id='62200000-0000-4000-8000-000000000003', current_period_start=:now, current_period_end=:end where id=:id")
				.param("now", Timestamp.from(clock.instant())).param("end", Timestamp.from(clock.instant().plusSeconds(30 * 86_400L)))
				.param("id", purchase.subscriptionId()).update();
		var appointment = completed(purchase, 1, clock.instant().minusSeconds(600));
		earnings.createForCompletedAppointment(appointment);
		earnings.createForCompletedAppointment(appointment);
		var value = jdbc.sql("select * from specialist_earning where appointment_id=:id")
				.param("id", appointment).query().singleRow();
		assertThat(value.get("earning_policy_version")).isEqualTo("staging-demo-paid-v1");
		assertThat(value.get("funding_payment_id")).isEqualTo(purchase.paymentId());
		assertThat(value.get("credit_allocation_minor")).isEqualTo(1_250L);
		assertThat(value.get("specialist_amount_minor")).isEqualTo(875L);
		assertThat(value.get("platform_allocation_minor")).isEqualTo(375L);
		assertThat(((Timestamp) value.get("settlement_available_at")).toInstant()).isEqualTo(clock.instant());
		assertThat(jdbc.sql("select credit_allocation_minor from service_credit_period where id=:id")
				.param("id", purchase.periodId()).query(Long.class).single()).isEqualTo(300_000);
		assertThat(eligibleEarnings(purchase)).isEqualTo(1);
	}

	@ParameterizedTest
	@CsvSource({"PLUS,5007,4,875,3500", "PREMIUM,10000,10,700,7000"})
	void bothDownwardRoundingsKeepTotalEarningsBelowCollectedMoney(String plan, long paid, int credits,
			long perSession, long total) {
		var purchase = purchase(plan, paid, "PAYOS", true);
		for (var ordinal = 1; ordinal <= credits; ordinal++) {
			earnings.createForCompletedAppointment(completed(purchase, ordinal, clock.instant().minusSeconds(600)));
		}
		assertThat(jdbc.sql("select distinct specialist_amount_minor from specialist_earning where specialist_account_id=:id")
				.param("id", purchase.specialistId()).query(Long.class).single()).isEqualTo(perSession);
		assertThat(payouts.earnings(purchase.specialistId()).balance().availableVnd()).isEqualTo(total).isLessThanOrEqualTo(paid);
		assertThat(total).isLessThanOrEqualTo(paid * 7_000 / 10_000);
	}

	@ParameterizedTest
	@ValueSource(strings={"FAKE", "MISSING_RECEIPT", "AMOUNT", "ORDER", "LINK", "REFERENCE", "REJECTED_RECEIPT", "DEMO_PERIOD", "CREDIT_COUNT"})
	void syntheticAndUnverifiedSourcesNeverReceiveRedeemableSnapshots(String mismatch) {
		var purchase = purchase("PLUS", 5_000, "FAKE".equals(mismatch) ? "FAKE" : "PAYOS",
				!"MISSING_RECEIPT".equals(mismatch));
		switch (mismatch) {
			case "AMOUNT" -> jdbc.sql("update payos_payment_webhook set amount_minor=5001 where payment_id=:id")
					.param("id", purchase.paymentId()).update();
			case "ORDER" -> jdbc.sql("update payos_payment_webhook set order_code=order_code+1 where payment_id=:id")
					.param("id", purchase.paymentId()).update();
			case "LINK" -> jdbc.sql("update payos_payment_webhook set payment_link_id='different-link' where payment_id=:id")
					.param("id", purchase.paymentId()).update();
			case "REFERENCE" -> jdbc.sql("update payos_payment_webhook set reference='different-reference' where payment_id=:id")
					.param("id", purchase.paymentId()).update();
			case "REJECTED_RECEIPT" -> jdbc.sql("update payos_payment_webhook set processing_status='VERIFIED_UNMATCHED',failure_code='AMOUNT_MISMATCH' where payment_id=:id")
					.param("id", purchase.paymentId()).update();
			case "DEMO_PERIOD" -> jdbc.sql("update service_credit_period set source='DEMO' where id=:id")
					.param("id", purchase.periodId()).update();
			case "CREDIT_COUNT" -> jdbc.sql("update service_credit_period set credit_policy_version='consultation-credit-v1',allocated_count=1 where id=:id")
					.param("id", purchase.periodId()).update();
			default -> { }
		}
		var appointment = completed(purchase, 1, clock.instant().minusSeconds(600));
		earnings.createForCompletedAppointment(appointment);
		assertLegacySnapshot(appointment);
		assertThat(eligibleEarnings(purchase)).isZero();
		assertConflict(() -> transactions.prepare(purchase.specialistId(), UUID.randomUUID(), "blocked-demo-" + UUID.randomUUID()),
				"SANDBOX_EARNINGS_REAL_PAYOUT_DISABLED");
		verifyNoInteractions(provider);
	}

	@ParameterizedTest
	@CsvSource({"staging,false", "production,true", "prod,true", "staging+production,true", "default,true"})
	void defaultOffAndProductionProfilesPreserveBaselineEconomics(String profiles, boolean enabled) {
		properties.setStagingDemoEnabled(enabled);
		setProfiles(profiles.split("\\+"));
		var purchase = purchase("PLUS", 5_000, "PAYOS", true);
		var completedAt = clock.instant().minusSeconds(600);
		var appointment = completed(purchase, 1, completedAt);
		earnings.createForCompletedAppointment(appointment);
		assertLegacySnapshot(appointment);
		assertThat(properties.getMinimumWithdrawalVnd()).isEqualTo(100_000);
		assertThat(properties.getSettlementHold()).isEqualTo(Duration.ofDays(7));
		var response = payouts.earnings(purchase.specialistId());
		assertThat(response.settlementHoldSeconds()).isNull();
		assertThat(json.valueToTree(response).has("settlementHoldSeconds")).isFalse();
		assertThat(response.settlementHoldDays()).isEqualTo(7);
		assertThat(jdbc.sql("select settlement_available_at from specialist_earning where appointment_id=:id")
				.param("id", appointment).query(Timestamp.class).single().toInstant()).isEqualTo(completedAt.plus(Duration.ofDays(7)));
	}

	@Test
	void enablingDemoAfterLegacyCreationNeverRevaluesOrRedeemsTheOld210000() {
		var purchase = purchase("PLUS", 5_000, "PAYOS", true);
		var appointment = completed(purchase, 1, clock.instant().minus(Duration.ofDays(8)));
		properties.setStagingDemoEnabled(false);
		earnings.createForCompletedAppointment(appointment);
		properties.setStagingDemoEnabled(true);
		earnings.createForCompletedAppointment(appointment);
		assertLegacySnapshot(appointment);
		assertConflict(() -> transactions.prepare(purchase.specialistId(), UUID.randomUUID(), "legacy-demo-gate-" + UUID.randomUUID()),
				"SANDBOX_EARNINGS_REAL_PAYOUT_DISABLED");
	}

	@ParameterizedTest
	@ValueSource(strings={"DEMO_UNKNOWN_PLAN", "FAKE_ORDINARY_PLAN"})
	void fundedBalancesCannotBeMixedWithLegacyFixtures(String fixtureType) {
		var funded = purchase("PLUS", 5_000, "PAYOS", true);
		for (var ordinal = 1; ordinal <= 3; ordinal++) {
			earnings.createForCompletedAppointment(completed(funded, ordinal, clock.instant().minusSeconds(600)));
		}
		var fixture = purchase("PLUS", 5_000, "FAKE", false, funded.specialistId());
		if ("DEMO_UNKNOWN_PLAN".equals(fixtureType)) {
			jdbc.sql("update service_credit_period set source='DEMO',plan_version='service-entitlement-v1' where id=:id")
					.param("id", fixture.periodId()).update();
		}
		else {
			var ordinary = jdbc.sql("select id::text from subscription_plan_version where plan_code='PLUS' and pricing_policy_version='upgrade-offset-v1' order by id limit 1")
					.query(String.class).single();
			jdbc.sql("update service_credit_period set plan_version=:plan where id=:id")
					.param("plan", ordinary).param("id", fixture.periodId()).update();
		}
		var appointment = completed(fixture, 1, clock.instant().minusSeconds(600));
		earnings.createForCompletedAppointment(appointment);
		assertLegacySnapshot(appointment);
		assertConflict(() -> transactions.prepare(funded.specialistId(), UUID.randomUUID(), "demo-mixed-fixture-" + UUID.randomUUID()),
				"SANDBOX_EARNINGS_REAL_PAYOUT_DISABLED");
		assertThat(jdbc.sql("select count(*) from specialist_payout where specialist_account_id=:id")
				.param("id", funded.specialistId()).query(Long.class).single()).isZero();
	}

	@Test
	void threeDemoSessionsReach2000OnlyAfterTheHoldAndExactDestinationConfirmation() {
		var purchase = purchase("PLUS", 5_000, "PAYOS", true);
		for (var ordinal = 1; ordinal <= 2; ordinal++) {
			earnings.createForCompletedAppointment(completed(purchase, ordinal, clock.instant().minusSeconds(600)));
		}
		earnings.createForCompletedAppointment(completed(purchase, 3, clock.instant().minusSeconds(599)));
		var pending = payouts.saveDestination(purchase.specialistId(),
				new SavePayoutDestinationRequest("BANK_ACCOUNT", "1234567890", "SYNTHETIC HOLDER", "970436"));
		assertConflict(() -> payouts.requestPayout(purchase.specialistId(), pending.id(), "demo-confirmation-required-01"),
				"PAYOUT_DESTINATION_CONFIRMATION_REQUIRED");
		var confirmed = destinations.confirm(UUID.randomUUID(), pending.id(), "demo-destination-confirmation-01",
				new ConfirmPayoutDestinationRequest(pending.version(), "ACCOUNT_HOLDER_CHECKED"));
		var view = payouts.earnings(purchase.specialistId());
		assertThat(view.balance().availableVnd()).isEqualTo(1_750);
		assertThat(view.balance().pendingSettlementVnd()).isEqualTo(875);
		assertThat(view.minimumWithdrawalVnd()).isEqualTo(2_000);
		assertThat(view.settlementHoldSeconds()).isEqualTo(600);
		assertConflict(() -> payouts.requestPayout(purchase.specialistId(), confirmed.id(), "demo-minimum-before-hold-01"),
				"PAYOUT_MINIMUM_NOT_REACHED");
		verifyNoInteractions(provider);
		installClock(Clock.offset(clock, Duration.ofSeconds(1)));
		var result = payouts.requestPayout(purchase.specialistId(), confirmed.id(), "demo-three-session-payout-01");
		assertThat(result.balance().paidVnd()).isEqualTo(2_625);
		assertThat(result.payouts()).singleElement().satisfies(payout -> assertThat(payout.amountVnd()).isEqualTo(2_625));
		payouts.requestPayout(purchase.specialistId(), confirmed.id(), "demo-three-session-payout-01");
		verify(provider).submit(eq("PAYOS"), any(PayoutProvider.Command.class));
		earnings.createForCompletedAppointment(completed(purchase, 4, clock.instant().minusSeconds(600)));
		assertConflict(() -> payouts.requestPayout(purchase.specialistId(), confirmed.id(), "demo-daily-cap-unchanged-01"),
				"PAYOUT_DAILY_LIMIT_REACHED");
		verifyNoMoreInteractions(provider);
		var payoutId = result.payouts().getFirst().id();
		assertThatThrownBy(() -> jdbc.sql("update specialist_payout set amount_minor=0 where id=:id")
				.param("id", payoutId).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("update specialist_payout set amount_minor=-1 where id=:id")
				.param("id", payoutId).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseKeepsFundingReferenceAndPolicySnapshotsConsistent() {
		var purchase = purchase("PLUS", 5_000, "PAYOS", true);
		var appointment = completed(purchase, 1, clock.instant().minusSeconds(600));
		earnings.createForCompletedAppointment(appointment);
		for (var assignment : List.of("funding_payment_id=null", "earning_policy_version='specialist-earning-v1'",
				"earning_policy_version='unknown-policy'", "funding_payment_id='" + UUID.randomUUID() + "'")) {
			assertThatThrownBy(() -> jdbc.sql("update specialist_earning set " + assignment + " where appointment_id=:id")
					.param("id", appointment).update()).isInstanceOf(DataIntegrityViolationException.class);
		}
		assertThat(eligibleEarnings(purchase)).isEqualTo(1);
	}

	@Test
	void retryRevalidatesVerifiedFundingAndTheStagingGate() {
		var purchase = purchase("PLUS", 5_000, "PAYOS", true);
		for (var ordinal = 1; ordinal <= 3; ordinal++) {
			earnings.createForCompletedAppointment(completed(purchase, ordinal, clock.instant().minusSeconds(600)));
		}
		var pending = payouts.saveDestination(purchase.specialistId(),
				new SavePayoutDestinationRequest("BANK_ACCOUNT", "1234567890", "SYNTHETIC HOLDER", "970436"));
		var confirmed = destinations.confirm(UUID.randomUUID(), pending.id(), "demo-retry-confirmation-01",
				new ConfirmPayoutDestinationRequest(pending.version(), "ACCOUNT_HOLDER_CHECKED"));
		when(provider.submit(eq("PAYOS"), any(PayoutProvider.Command.class)))
				.thenReturn(new PayoutProvider.Result("FAILED", null, "PAYOUT_PROVIDER_REJECTED"));
		var result = payouts.requestPayout(purchase.specialistId(), confirmed.id(), "demo-failed-payout-01");
		var payoutId = result.payouts().getFirst().id();
		var attemptId = jdbc.sql("select id from specialist_payout_attempt where payout_id=:id")
				.param("id", payoutId).query(UUID.class).single();
		properties.setStagingDemoEnabled(false);
		assertThatThrownBy(() -> transactions.retry(UUID.randomUUID(), payoutId, attemptId,
				"demo-retry-gate-off-01", UUID.randomUUID())).isInstanceOf(ApiException.class);
		properties.setStagingDemoEnabled(true);
		jdbc.sql("update payos_payment_webhook set processing_status='VERIFIED_UNMATCHED',failure_code='AMOUNT_MISMATCH' where payment_id=:id")
				.param("id", purchase.paymentId()).update();
		assertThatThrownBy(() -> transactions.retry(UUID.randomUUID(), payoutId, attemptId,
				"demo-retry-proof-invalid-01", UUID.randomUUID())).isInstanceOf(ApiException.class);
		assertThat(jdbc.sql("select count(*) from specialist_payout_attempt where payout_id=:id")
				.param("id", payoutId).query(Long.class).single()).isEqualTo(1);
		verify(provider).submit(eq("PAYOS"), any(PayoutProvider.Command.class));
		verifyNoMoreInteractions(provider);
	}

	private void setProfiles(String... profiles) {
		var environment = new MockEnvironment();
		environment.setActiveProfiles(profiles);
		properties.setEnvironment(environment);
	}

	private void installClock(Clock value) {
		clock = value;
		earnings = transactional(new SpecialistEarningService(jdbc, properties, clock, settlementGate), SpecialistEarningService.class);
		transactions = transactional(new PayoutTransactions(jdbc, properties, clock, audit), PayoutTransactions.class);
		var banks = mock(PayoutBankDirectory.class);
		when(banks.banks()).thenReturn(new PayoutBankDirectory.BankList(clock.instant(),
				List.of(new PayoutBankDirectory.Bank("970436", "Synthetic bank", "SYNTHETIC"))));
		destinations = transactional(new PayoutDestinationService(jdbc, properties, new PayoutDestinationCipher(properties),
				banks, json, clock, transactionManager, audit), PayoutDestinationService.class);
		payouts = transactional(new SpecialistPayoutService(jdbc, properties, destinations, transactions, provider, clock), SpecialistPayoutService.class);
	}

	private <T> T transactional(T target, Class<T> type) {
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
		return type.cast(proxy.getProxy());
	}

	private void assertConflict(Runnable command, String code) {
		assertThatThrownBy(command::run).isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo(code));
	}

	private void assertLegacySnapshot(UUID appointmentId) {
		var value = jdbc.sql("select earning_policy_version,funding_payment_id,specialist_amount_minor from specialist_earning where appointment_id=:id")
				.param("id", appointmentId).query().singleRow();
		assertThat(value.get("earning_policy_version")).isEqualTo("specialist-earning-v1");
		assertThat(value.get("funding_payment_id")).isNull();
		assertThat(value.get("specialist_amount_minor")).isEqualTo(210_000L);
	}

	private long eligibleEarnings(Purchase purchase) {
		return jdbc.sql("select count(*) from specialist_earning e where e.specialist_account_id=:id and "
				+ StagingDemoEarningSource.ELIGIBLE_EARNING).param("id", purchase.specialistId()).query(Long.class).single();
	}

	private Purchase purchase(String plan, long amount, String paymentProvider, boolean receipt) {
		return purchase(plan, amount, paymentProvider, receipt, UUID.randomUUID());
	}

	private Purchase purchase(String plan, long amount, String paymentProvider, boolean receipt, UUID specialistId) {
		var userId = UUID.randomUUID();
		var subscriptionId = UUID.randomUUID();
		var paymentId = UUID.randomUUID();
		var periodId = UUID.randomUUID();
		var planId = UUID.fromString("PLUS".equals(plan) ? "62200000-0000-4000-8000-000000000002" : "62200000-0000-4000-8000-000000000003");
		var paidAt = clock.instant().minus(Duration.ofDays(1));
		var periodEnd = paidAt.plus(Duration.ofDays(30));
		var orderCode = jdbc.sql("select nextval('payment_order_code_seq')").query(Long.class).single();
		jdbc.sql("""
				insert into specialist_profile(account_id,display_name,biography,years_experience,timezone,approval_status,
				 submitted_at,reviewed_at,reviewed_by)
				values(:id,'Demo payout specialist','Synthetic fixture',5,'Asia/Ho_Chi_Minh','APPROVED',now(),now(),:admin)
				on conflict(account_id) do nothing
				""").param("id", specialistId).param("admin", UUID.randomUUID()).update();
		jdbc.sql("""
				insert into user_subscription(id,user_id,plan_version_id,status,current_period_start,current_period_end,
				 idempotency_key,created_at,updated_at)
				values(:id,:userId,:plan,'ACTIVE',:start,:end,:key,:start,:start)
				""").param("id", subscriptionId).param("userId", userId).param("plan", planId)
				.param("start", Timestamp.from(paidAt)).param("end", Timestamp.from(periodEnd))
				.param("key", "demo-subscription-" + subscriptionId).update();
		jdbc.sql("""
				insert into payment_transaction(id,subscription_id,target_plan_version_id,payment_provider,momo_order_id,
				 momo_request_id,order_code,payos_payment_link_id,payos_reference,amount_minor,currency,status,purpose,
				 idempotency_key,paid_at,expires_at,created_at,updated_at)
				values(:id,:subscription,:plan,:provider,:orderId,:requestId,:code,:link,:reference,:amount,'VND',
				 'SUCCEEDED','PURCHASE',:key,:paidAt,:expiry,:createdAt,:paidAt)
				""").param("id", paymentId).param("subscription", subscriptionId).param("plan", planId)
				.param("provider", paymentProvider).param("orderId", "PAYOS".equals(paymentProvider) ? null : "fake-" + paymentId)
				.param("requestId", "PAYOS".equals(paymentProvider) ? null : "fake-request-" + paymentId)
				.param("code", orderCode).param("link", "PAYOS".equals(paymentProvider) ? "link-" + paymentId : null)
				.param("reference", "PAYOS".equals(paymentProvider) ? "reference-" + paymentId : null)
				.param("amount", amount).param("key", "demo-payment-" + paymentId).param("paidAt", Timestamp.from(paidAt))
				.param("expiry", Timestamp.from(paidAt.plusSeconds(600))).param("createdAt", Timestamp.from(paidAt.minusSeconds(60))).update();
		jdbc.sql("""
				insert into service_credit_period(id,account_id,plan_version,credit_policy_version,package_code,source,
				 source_reference,period_start,period_end,allocated_count,credit_allocation_minor,created_at,updated_at)
				values(:id,:userId,:planVersion,'consultation-credit-v2',:plan,'PAID',:source,:start,:end,:credits,300000,:start,:start)
				""").param("id", periodId).param("userId", userId).param("planVersion", planId.toString()).param("plan", plan)
				.param("source", subscriptionId.toString()).param("start", Timestamp.from(paidAt)).param("end", Timestamp.from(periodEnd))
				.param("credits", "PLUS".equals(plan) ? 4 : 10).update();
		if (receipt && "PAYOS".equals(paymentProvider)) {
			jdbc.sql("""
					insert into payos_payment_webhook(id,payment_id,contract_version,source,deduplication_key,order_code,
					 amount_minor,payment_link_id,reference,code,success,payload_sha256,signature_key_version,
					 signature_verified_at,processing_status,received_at,processed_at,acknowledged_at)
					values(:id,:payment,'payos-collect-v1','WEBHOOK',:hash,:code,:amount,:link,:reference,'00',true,
					 :hash,'test-v1',:paidAt,'PROCESSED',:paidAt,:paidAt,:paidAt)
					""").param("id", UUID.randomUUID()).param("payment", paymentId)
					.param("hash", (paymentId.toString().replace("-", "") + paymentId.toString().replace("-", "")))
					.param("code", orderCode).param("amount", amount).param("link", "link-" + paymentId)
					.param("reference", "reference-" + paymentId).param("paidAt", Timestamp.from(paidAt)).update();
		}
		return new Purchase(specialistId, userId, subscriptionId, paymentId, periodId);
	}

	private UUID completed(Purchase purchase, int ordinal, Instant completedAt) {
		var creditId = UUID.randomUUID();
		var slotId = UUID.randomUUID();
		var appointmentId = UUID.randomUUID();
		var start = jdbc.sql("select end_at from availability_slot where specialist_account_id=:id order by end_at desc limit 1")
				.param("id", purchase.specialistId()).query(Timestamp.class).optional()
				.map(end -> end.toInstant().plusSeconds(3600)).orElse(clock.instant().plusSeconds(86_400));
		jdbc.sql("""
				insert into service_credit(id,period_id,ordinal,state,appointment_id,created_at,updated_at)
				values(:id,:period,:ordinal,'HELD',:appointment,now(),now())
				""").param("id", creditId).param("period", purchase.periodId()).param("ordinal", ordinal)
				.param("appointment", appointmentId).update();
		jdbc.sql("""
				insert into availability_slot(id,specialist_account_id,start_at,end_at,timezone,modality,idempotency_key,created_at,updated_at)
				values(:id,:specialist,:start,:end,'Asia/Ho_Chi_Minh','IN_APP_CHAT',:key,now(),now())
				""").param("id", slotId).param("specialist", purchase.specialistId()).param("start", Timestamp.from(start))
				.param("end", Timestamp.from(start.plusSeconds(3600))).param("key", "demo-slot-" + slotId).update();
		jdbc.sql("""
				insert into appointment(id,user_account_id,specialist_account_id,availability_slot_id,service_credit_id,status,
				 modality,scheduled_start_at,scheduled_end_at,display_timezone,requested_at,decision_deadline_at,
				 idempotency_key,created_at,updated_at)
				values(:id,:user,:specialist,:slot,:credit,'REQUESTED','IN_APP_CHAT',:start,:end,'Asia/Ho_Chi_Minh',
				 now(),:deadline,:key,now(),now())
				""").param("id", appointmentId).param("user", purchase.userId()).param("specialist", purchase.specialistId())
				.param("slot", slotId).param("credit", creditId).param("start", Timestamp.from(start))
				.param("end", Timestamp.from(start.plusSeconds(3600))).param("deadline", Timestamp.from(start.minusSeconds(3600)))
				.param("key", "demo-appointment-" + appointmentId).update();
		jdbc.sql("""
				update appointment set status='COMPLETED',decided_at=:earned,decision_reason='SPECIALIST_ACCEPTED',
				 session_outcome='COMPLETED',session_outcome_reason='EVIDENCE_REQUIREMENTS_MET',
				 session_policy_version='chat-session-completion-v1',session_ended_at=:earned,session_settled_at=:earned,
				 completion_fact_id=:fact where id=:id
				""").param("id", appointmentId).param("earned", Timestamp.from(completedAt)).param("fact", UUID.randomUUID()).update();
		jdbc.sql("update service_credit set state='CONSUMED' where id=:id").param("id", creditId).update();
		return appointmentId;
	}

	private record Purchase(UUID specialistId, UUID userId, UUID subscriptionId, UUID paymentId, UUID periodId) { }
}

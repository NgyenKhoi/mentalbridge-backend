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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.mentalbridge.consultation.dispute.AppointmentDisputeService;
import com.mentalbridge.consultation.dispute.OpenAppointmentDisputeRequest;
import com.mentalbridge.consultation.dispute.ResolveAppointmentDisputeRequest;
import com.mentalbridge.consultation.shared.ApiException;
import com.mentalbridge.consultation.specialist.ConsultationAdminAuditOutbox;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties="mentalbridge.consultation.payout.reconciliation-interval=PT24H")
@AutoConfigureMockMvc
class SpecialistPayoutFlowIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired SpecialistPayoutService payouts;
	@Autowired PayoutTransactions transactions;
	@Autowired PayoutProperties properties;
	@Autowired AppointmentDisputeService disputes;
	@Autowired SpecialistEarningService earningService;
	@Autowired Clock clock;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired ConsultationAdminAuditOutbox audit;
	@org.springframework.test.context.bean.override.mockito.MockitoBean PayoutReconciliationScheduler scheduler;

	@Test
	void payosInitialAdmissionRequiresExactCurrentDestinationConfirmation() {
		var payosClock = Clock.fixed(clock.instant(), clock.getZone());
		var payosProperties = new PayoutProperties();
		payosProperties.setMode("PAYOS");
		payosProperties.setEncryptionKey(properties.getEncryptionKey());
		payosProperties.setPayosClientId("synthetic-client");
		payosProperties.setPayosApiKey("synthetic-api");
		payosProperties.setPayosChecksumKey("synthetic-checksum");
		var environment = new MockEnvironment();
		environment.setActiveProfiles("staging");
		payosProperties.setEnvironment(environment);
		assertThat(payosProperties.ready()).isTrue();
		var banks = mock(PayoutBankDirectory.class);
		when(banks.banks()).thenReturn(new PayoutBankDirectory.BankList(payosClock.instant(),
				List.of(new PayoutBankDirectory.Bank("970436", "Synthetic bank", "SYNTHETIC"))));
		var destinationProxy = new ProxyFactory(new PayoutDestinationService(jdbc, payosProperties,
				new PayoutDestinationCipher(payosProperties), banks, json, payosClock, transactionManager, audit));
		destinationProxy.setProxyTargetClass(true);
		destinationProxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
		var payosDestinations = (PayoutDestinationService) destinationProxy.getProxy();
		var transactionProxy = new ProxyFactory(new PayoutTransactions(jdbc, payosProperties, payosClock, audit));
		transactionProxy.setProxyTargetClass(true);
		transactionProxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
		var payosTransactions = (PayoutTransactions) transactionProxy.getProxy();
		var provider = mock(PayoutProvider.class);
		when(provider.submit(eq("PAYOS"), any(PayoutProvider.Command.class))).thenReturn(new PayoutProvider.Result(
				"SUCCEEDED", "synthetic-payos-" + UUID.randomUUID(), null, "synthetic-child-" + UUID.randomUUID()));
		var payosPayouts = new SpecialistPayoutService(jdbc, payosProperties, payosDestinations,
				payosTransactions, provider, payosClock);
		var owner = UUID.randomUUID();
		ordinaryPricingEarning(owner);
		assertThat(payosPayouts.earnings(owner).balance().availableVnd()).isEqualTo(210_000);
		var pending = payosPayouts.saveDestination(owner,
				new SavePayoutDestinationRequest("BANK_ACCOUNT", "1234567890", "SYNTHETIC HOLDER", "970436"));
		assertThat(pending.provider()).isEqualTo("PAYOS");
		assertThat(pending.status()).isEqualTo("PENDING_CONFIRMATION");
		assertThat(pending.usableForPayout()).isFalse();
		assertThat(payosDestinations.current(owner).id()).isEqualTo(pending.id());
		var admissionKey = "payos-confirmation-admission-01";
		assertThatThrownBy(() -> payosPayouts.requestPayout(owner, pending.id(), admissionKey))
				.isInstanceOfSatisfying(ApiException.class,
						error -> assertThat(error.code()).isEqualTo("PAYOUT_DESTINATION_CONFIRMATION_REQUIRED"));
		assertPayoutAdmissionCounts(owner, 0);
		verifyNoInteractions(provider);

		var confirmed = payosDestinations.confirm(UUID.randomUUID(), pending.id(), "payos-exact-confirmation-01",
				new ConfirmPayoutDestinationRequest(pending.version(), "ACCOUNT_HOLDER_CHECKED"));
		assertThat(confirmed.id()).isEqualTo(pending.id());
		assertThat(confirmed.status()).isEqualTo("VERIFIED");
		assertThat(confirmed.version()).isEqualTo(pending.version() + 1);
		assertThat(confirmed.usableForPayout()).isTrue();
		verifyNoInteractions(provider);
		var admitted = payosPayouts.requestPayout(owner, confirmed.id(), admissionKey);
		assertThat(admitted.balance().paidVnd()).isEqualTo(210_000);
		assertThat(admitted.payouts()).singleElement().satisfies(payout -> {
			assertThat(payout.destinationId()).isEqualTo(confirmed.id());
			assertThat(payout.provider()).isEqualTo("PAYOS");
			assertThat(payout.status()).isEqualTo("SUCCEEDED");
		});
		assertPayoutAdmissionCounts(owner, 1);
		var command = ArgumentCaptor.forClass(PayoutProvider.Command.class);
		verify(provider).submit(eq("PAYOS"), command.capture());
		assertThat(command.getValue().amountVnd()).isEqualTo(210_000);
		assertThat(command.getValue().destinationType()).isEqualTo("BANK_ACCOUNT");

		var nextEarning = ordinaryPricingEarning(owner);
		jdbc.sql("update specialist_payout set requested_on=:previousDay where id=:id")
				.param("previousDay", LocalDate.now(payosClock).minusDays(1))
				.param("id", admitted.payouts().getFirst().id()).update();
		assertThat(jdbc.sql("select count(*) from specialist_payout where specialist_account_id=:owner and requested_on=:day")
				.param("owner", owner).param("day", LocalDate.now(payosClock)).query(Long.class).single()).isZero();
		assertThat(payosPayouts.earnings(owner).balance().availableVnd()).isEqualTo(210_000);
		var edited = payosPayouts.saveDestination(owner,
				new SavePayoutDestinationRequest("BANK_ACCOUNT", "1234567891", "SYNTHETIC HOLDER", "970436"));
		assertThat(edited.id()).isNotEqualTo(confirmed.id());
		assertThat(edited.status()).isEqualTo("PENDING_CONFIRMATION");
		assertThat(edited.usableForPayout()).isFalse();
		assertThat(payosDestinations.current(owner).id()).isEqualTo(edited.id());
		assertThat(payosDestinations.destination(owner, confirmed.id()).status()).isEqualTo("DISABLED");
		for (var destinationId : List.of(confirmed.id(), edited.id())) {
			assertThatThrownBy(() -> payosPayouts.requestPayout(owner, destinationId, "payos-edited-bank-" + destinationId))
					.isInstanceOfSatisfying(ApiException.class,
							error -> assertThat(error.code()).isEqualTo("PAYOUT_DESTINATION_CONFIRMATION_REQUIRED"));
			assertPayoutAdmissionCounts(owner, 1);
			assertThat(payosPayouts.earnings(owner).balance().availableVnd()).isEqualTo(210_000);
			assertThat(jdbc.sql("select count(*) from specialist_payout_item i join specialist_earning e on e.id=i.earning_id where e.consumed_credit_id=:id")
					.param("id", nextEarning.creditId()).query(Long.class).single()).isZero();
			verifyNoMoreInteractions(provider);
		}
	}

	private Fixture ordinaryPricingEarning(UUID specialistId) {
		var fixture = earningFixture(clock.instant().minusSeconds(8 * 86_400L), 210_000, specialistId);
		var planVersion = jdbc.sql("select id from subscription_plan_version where plan_code='PLUS' and pricing_policy_version='upgrade-offset-v1' order by id limit 1")
				.query(UUID.class).single().toString();
		jdbc.sql("update specialist_earning set plan_version=:version where consumed_credit_id=:id")
				.param("version", planVersion).param("id", fixture.creditId()).update();
		jdbc.sql("update service_credit_period set plan_version=:version where id=(select period_id from service_credit where id=:id)")
				.param("version", planVersion).param("id", fixture.creditId()).update();
		return fixture;
	}

	private void assertPayoutAdmissionCounts(UUID owner, long expected) {
		assertThat(jdbc.sql("select count(*) from specialist_payout where specialist_account_id=:owner")
				.param("owner", owner).query(Long.class).single()).isEqualTo(expected);
		assertThat(jdbc.sql("select count(*) from specialist_payout_attempt a join specialist_payout p on p.id=a.payout_id where p.specialist_account_id=:owner")
				.param("owner", owner).query(Long.class).single()).isEqualTo(expected);
		assertThat(jdbc.sql("select count(*) from specialist_payout_item i join specialist_payout p on p.id=i.payout_id where p.specialist_account_id=:owner")
				.param("owner", owner).query(Long.class).single()).isEqualTo(expected);
	}

	@Test
	void noEarningIsMaterializedWhileTheDisputeGateIsClosed() {
		var fixture = disputableEarning();
		jdbc.sql("delete from specialist_earning where consumed_credit_id=:id").param("id", fixture.creditId()).update();
		var dispute = openDispute(fixture);
		earningService.createForCompletedAppointment(dispute.appointmentId());
		assertThat(jdbc.sql("select count(*) from specialist_earning where appointment_id=:id")
				.param("id", dispute.appointmentId()).query(Long.class).single()).isZero();
		disputes.resolve(UUID.randomUUID(), dispute.id(), 0, "materialize-upheld-key-01",
				new ResolveAppointmentDisputeRequest(ResolveAppointmentDisputeRequest.Outcome.UPHOLD_RECORDED_OUTCOME,
						ResolveAppointmentDisputeRequest.ReasonCode.EVIDENCE_SUPPORTS_RECORDED_OUTCOME));
		earningService.createForCompletedAppointment(dispute.appointmentId());
		earningService.createForCompletedAppointment(dispute.appointmentId());
		assertThat(jdbc.sql("select count(*) from specialist_earning where appointment_id=:id")
				.param("id", dispute.appointmentId()).query(Long.class).single()).isOne();
	}

	@Test
	void openDisputeBlocksAnAlreadyAvailableEarningAndUpheldResolutionRestoresEligibility() {
		var fixture = disputableEarning();
		var destination = payouts.saveDestination(fixture.specialistId(),
				new SavePayoutDestinationRequest("MOMO_WALLET", "0900000622", "Synthetic specialist", null));
		assertThat(payouts.earnings(fixture.specialistId()).balance().availableVnd()).isEqualTo(210_000);
		var dispute = openDispute(fixture);
		assertThat(payouts.earnings(fixture.specialistId()).balance().availableVnd()).isZero();
		assertThatThrownBy(() -> transactions.prepare(fixture.specialistId(), destination.id(), "disputed-payout-key-01"))
				.isInstanceOfSatisfying(ApiException.class,
						error -> assertThat(error.code()).isEqualTo("PAYOUT_MINIMUM_NOT_REACHED"));
		disputes.resolve(UUID.randomUUID(), dispute.id(), 0, "uphold-payout-dispute-01",
				new ResolveAppointmentDisputeRequest(ResolveAppointmentDisputeRequest.Outcome.UPHOLD_RECORDED_OUTCOME,
						ResolveAppointmentDisputeRequest.ReasonCode.EVIDENCE_SUPPORTS_RECORDED_OUTCOME));
		assertThat(payouts.earnings(fixture.specialistId()).balance().availableVnd()).isEqualTo(210_000);
		var paid = payouts.requestPayout(fixture.specialistId(), destination.id(), "upheld-payout-key-01");
		assertThat(paid.balance().paidVnd()).isEqualTo(210_000);
	}

	@Test
	void creditReleaseResolutionBlocksMaterializationAndRetryOfAnExistingFailedPayout() {
		var fixture = disputableEarning();
		var destination = payouts.saveDestination(fixture.specialistId(),
				new SavePayoutDestinationRequest("MOMO_WALLET", "0900000623", "Synthetic specialist", null));
		var prepared = transactions.prepare(fixture.specialistId(), destination.id(), "release-payout-key-01");
		transactions.reconcile(prepared.payoutId(), prepared.attemptId(), new PayoutProvider.Result("FAILED", null, "REJECTED"));
		var dispute = openDispute(fixture);
		earningService.createForCompletedAppointment(dispute.appointmentId());
		disputes.resolve(UUID.randomUUID(), dispute.id(), 0, "release-payout-dispute-01",
				new ResolveAppointmentDisputeRequest(ResolveAppointmentDisputeRequest.Outcome.RELEASE_USER_CREDIT,
						ResolveAppointmentDisputeRequest.ReasonCode.TECHNICAL_FAILURE_CONFIRMED));
		earningService.createForCompletedAppointment(dispute.appointmentId());
		assertThat(payouts.earnings(fixture.specialistId()).balance().availableVnd()).isZero();
		assertThatThrownBy(() -> transactions.retry(UUID.randomUUID(), prepared.payoutId(), prepared.attemptId(), "release-retry-key-01", UUID.randomUUID()))
				.isInstanceOfSatisfying(ApiException.class,
						error -> assertThat(error.code()).isEqualTo("PAYOUT_RETRY_NOT_AVAILABLE"));
		assertThat(jdbc.sql("select count(*) from specialist_earning where consumed_credit_id=:id")
				.param("id", fixture.creditId()).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from specialist_payout_attempt where payout_id=:id")
				.param("id", prepared.payoutId()).query(Long.class).single()).isOne();
	}

	private Fixture disputableEarning() {
		var fixture = earningFixture(Instant.now().minusSeconds(8 * 86_400L), 210_000);
		jdbc.sql("update appointment set session_settled_at=now() where service_credit_id=:id")
				.param("id", fixture.creditId()).update();
		return fixture;
	}

	private com.mentalbridge.consultation.dispute.AppointmentDisputeResponse openDispute(Fixture fixture) {
		var appointmentId = jdbc.sql("select id from appointment where service_credit_id=:id")
				.param("id", fixture.creditId()).query(UUID.class).single();
		var userId = jdbc.sql("select user_account_id from appointment where id=:id")
				.param("id", appointmentId).query(UUID.class).single();
		return disputes.open(userId, "USER", appointmentId, "earning-dispute-" + appointmentId,
				new OpenAppointmentDisputeRequest(OpenAppointmentDisputeRequest.ReasonCode.OUTCOME_INCORRECT, null, null));
	}

	@Test
	void sandboxEarningCannotEnterARealTransferEvenWhenPayoutModeChanges() {
		var fixture = earningFixture(Instant.now().minusSeconds(8 * 86_400L), 210_000);
		jdbc.sql("update specialist_earning set plan_version='62200000-0000-4000-8000-000000000002' where consumed_credit_id=:id")
				.param("id", fixture.creditId()).update();
		var previousMode = properties.getMode();
		try {
			properties.setMode("MOMO");
			assertThatThrownBy(() -> transactions.prepare(fixture.specialistId(), UUID.randomUUID(),
					"sandbox-real-transfer-blocked-01"))
					.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code())
							.isEqualTo("SANDBOX_EARNINGS_REAL_PAYOUT_DISABLED"));
		}
		finally {
			properties.setMode(previousMode);
		}
		assertThat(jdbc.sql("select count(*) from specialist_payout where specialist_account_id=:id")
				.param("id", fixture.specialistId()).query(Long.class).single()).isZero();
	}

	@Test
	void earningResponsePreservesTheAllocationSnapshotInsteadOfAssumingOneFixedAmount() throws Exception {
		var fixture = earningFixture(Instant.now().minusSeconds(8 * 86_400L), 175_000);

		mvc.perform(get("/api/v1/specialist/earnings").with(specialist(fixture.specialistId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.earnings[0].creditAllocationVnd").value(250000))
				.andExpect(jsonPath("$.earnings[0].earningAmountVnd").value(175000))
				.andExpect(jsonPath("$.earnings[0].sharePercent").value(70));
	}

	@Test
	void specialistWithdrawsSettledEarningOnceThroughDeterministicFake() throws Exception {
		var fixture = earningFixture(Instant.now().minusSeconds(8 * 86_400L), 210_000);

		mvc.perform(get("/api/v1/specialist/earnings").with(specialist(fixture.specialistId())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.balance.availableVnd").value(210000))
				.andExpect(jsonPath("$.balance.pendingSettlementVnd").value(0))
				.andExpect(jsonPath("$.earnings[0].consumedCreditId").value(fixture.creditId().toString()))
				.andExpect(jsonPath("$.earnings[0].creditAllocationVnd").value(300000))
				.andExpect(jsonPath("$.earnings[0].sharePercent").value(70));

		var destinationResult = mvc.perform(put("/api/v1/specialist/payout-destination")
				.with(specialist(fixture.specialistId())).contentType(MediaType.APPLICATION_JSON)
				.content("{\"destinationType\":\"MOMO_WALLET\",\"accountReference\":\"0912345678\",\"accountHolderName\":\"Nguyen Thu Ha\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.displayHint").value("•••• 5678"))
				.andExpect(jsonPath("$.provider").value("FAKE")).andReturn();
		var destinationId = UUID.fromString(json.readTree(destinationResult.getResponse().getContentAsByteArray())
				.get("id").asText());

		var body = "{\"destinationId\":\"%s\"}".formatted(destinationId);
		mvc.perform(post("/api/v1/specialist/payouts").with(specialist(fixture.specialistId()))
				.header("Idempotency-Key", "withdraw-all-available-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andExpect(jsonPath("$.balance.paidVnd").value(210000))
				.andExpect(jsonPath("$.payouts[0].status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.payouts[0].providerReference").value(org.hamcrest.Matchers.startsWith("fake-")));
		mvc.perform(post("/api/v1/specialist/payouts").with(specialist(fixture.specialistId()))
				.header("Idempotency-Key", "withdraw-all-available-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andExpect(jsonPath("$.payouts.length()").value(1));
		mvc.perform(post("/api/v1/specialist/payouts").with(specialist(fixture.specialistId()))
				.header("Idempotency-Key", "withdraw-second-command-0002")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYOUT_DAILY_LIMIT_REACHED"));
		mvc.perform(get("/api/v1/admin/payouts").with(specialist(fixture.specialistId())))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/admin/payouts").with(admin(UUID.randomUUID())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(org.hamcrest.Matchers.greaterThan(0)))
				.andExpect(jsonPath("$.items[?(@.specialistAccountId == '%s')].destinationHint".formatted(fixture.specialistId()))
						.value(org.hamcrest.Matchers.hasItem("•••• 5678")));

		assertThat(jdbc.sql("select count(*) from specialist_payout where specialist_account_id=:id")
				.param("id", fixture.specialistId()).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select destination_ciphertext from specialist_payout_destination where id=:id")
				.param("id", destinationId).query(String.class).single()).doesNotContain("0912345678");
	}

	@Test
	void holdAndMinimumAreEnforcedAndOtherRolesCannotReadEarnings() throws Exception {
		var pending = earningFixture(Instant.now(), 210_000);
		mvc.perform(get("/api/v1/specialist/earnings").with(specialist(pending.specialistId())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.balance.pendingSettlementVnd").value(210000))
				.andExpect(jsonPath("$.balance.availableVnd").value(0));
		mvc.perform(get("/api/v1/specialist/earnings").with(user(UUID.randomUUID())))
				.andExpect(status().isForbidden());
		var destinationResult = mvc.perform(put("/api/v1/specialist/payout-destination")
				.with(specialist(pending.specialistId())).contentType(MediaType.APPLICATION_JSON)
				.content("{\"destinationType\":\"MOMO_WALLET\",\"accountReference\":\"0987654321\",\"accountHolderName\":\"Nguyen Thu Ha\"}"))
				.andExpect(status().isOk()).andReturn();
		var destinationId = json.readTree(destinationResult.getResponse().getContentAsByteArray()).get("id").asText();
		mvc.perform(post("/api/v1/specialist/payouts").with(specialist(pending.specialistId()))
				.header("Idempotency-Key", "pending-minimum-command-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"destinationId\":\"%s\"}".formatted(destinationId)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYOUT_MINIMUM_NOT_REACHED"));
	}

	@Test
	void definiteProviderFailureCanRetryTheSameLogicalPayoutWithoutDoublePaying() throws Exception {
		var fixture = earningFixture(Instant.now().minusSeconds(8 * 86_400L), 210_000);
		var destination = payouts.saveDestination(fixture.specialistId(),
				new SavePayoutDestinationRequest("MOMO_WALLET", "0900000001", "Nguyen Thu Ha", null));
		payouts.earnings(fixture.specialistId());

		var first = transactions.prepare(fixture.specialistId(), destination.id(), "retry-provider-command-0001");
		transactions.reconcile(first.payoutId(), first.attemptId(),
				new PayoutProvider.Result("FAILED", null, "PROVIDER_REJECTED"));
		var replay = transactions.prepare(fixture.specialistId(), destination.id(), "retry-provider-command-0001");
		assertThat(replay.submit()).isFalse();
		assertThat(replay.attemptId()).isEqualTo(first.attemptId());
		var retry = transactions.retry(UUID.randomUUID(), first.payoutId(), first.attemptId(), "admin-retry-command-0001", UUID.randomUUID());
		transactions.reconcile(first.payoutId(), first.attemptId(), new PayoutProvider.Result("SUCCEEDED", "late-old-result", null));
		assertThat(payouts.earnings(fixture.specialistId()).balance().paidVnd()).isZero();

		assertThat(retry.payoutId()).isEqualTo(first.payoutId());
		assertThat(retry.attemptId()).isNotEqualTo(first.attemptId());
		assertThat(retry.submit()).isTrue();
		transactions.reconcile(retry.payoutId(), retry.attemptId(),
				new PayoutProvider.Result("SUCCEEDED", "fake-retry-success", null));
		assertThat(payouts.earnings(fixture.specialistId()).payouts().getFirst().providerReference())
				.isEqualTo("fake-retry-success");
		mvc.perform(get("/api/v1/admin/payouts").with(admin(UUID.randomUUID())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.payoutId == '%s')].providerReference".formatted(first.payoutId()))
						.value(org.hamcrest.Matchers.hasItem("fake-retry-success")))
				.andExpect(jsonPath("$.items[?(@.payoutId == '%s')].earningCount".formatted(first.payoutId()))
						.value(org.hamcrest.Matchers.hasItem(1)));

		assertThat(jdbc.sql("select count(*) from specialist_payout where specialist_account_id=:id")
				.param("id", fixture.specialistId()).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from specialist_payout_attempt where payout_id=:id")
				.param("id", first.payoutId()).query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("select status from specialist_earning where consumed_credit_id=:id")
				.param("id", fixture.creditId()).query(String.class).single()).isEqualTo("PAID");
	}

	@Test
	void momoCallbackRejectsAmountMismatchAndDeduplicatesAValidProviderEvent() {
		var fixture = earningFixture(Instant.now().minusSeconds(8 * 86_400L), 210_000);
		var destination = payouts.saveDestination(fixture.specialistId(),
				new SavePayoutDestinationRequest("MOMO_WALLET", "0900000002", "Nguyen Thu Ha", null));
		payouts.earnings(fixture.specialistId());
		var prepared = transactions.prepare(fixture.specialistId(), destination.id(), "momo-callback-command-0001");
		var requestId = "momo-request-0001";
		jdbc.sql("update specialist_payout_destination set payout_provider='MOMO' where id=:id")
				.param("id", destination.id()).update();
		jdbc.sql("with p as (update specialist_payout set payout_provider='MOMO' where id=:p returning id) update specialist_payout_attempt set payout_provider='MOMO',provider_idempotency_key=:key where id=:a")
				.param("p", prepared.payoutId()).param("key", requestId).param("a", prepared.attemptId()).update();

		var mismatch = momoCallback(prepared, requestId, prepared.amountVnd() - 1);
		assertThatThrownBy(() -> transactions.reconcileMomo(mismatch, "a".repeat(64)))
				.isInstanceOf(ApiException.class)
				.satisfies(error -> assertThat(((ApiException) error).code())
						.isEqualTo("MOMO_PAYOUT_CALLBACK_MISMATCH"));

		var valid = momoCallback(prepared, requestId, prepared.amountVnd());
		assertThat(jdbc.sql("select count(*) from payout_provider_event where payout_attempt_id=:id")
				.param("id", prepared.attemptId()).query(Long.class).single()).isZero();
		transactions.reconcileMomo(valid, "b".repeat(64));
		assertThat(jdbc.sql("select count(*) from payout_provider_event where payout_attempt_id=:id")
				.param("id", prepared.attemptId()).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from specialist_payout_status_history where payout_id=:id")
				.param("id", prepared.payoutId()).query(Long.class).single()).isEqualTo(2);
		transactions.reconcileMomo(valid, "b".repeat(64));

		assertThat(jdbc.sql("select status from specialist_payout where id=:id")
				.param("id", prepared.payoutId()).query(String.class).single()).isEqualTo("SUCCEEDED");
		assertThat(jdbc.sql("select count(*) from payout_provider_event where payout_attempt_id=:id")
				.param("id", prepared.attemptId()).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from specialist_payout_status_history where payout_id=:id")
				.param("id", prepared.payoutId()).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void failedItemsRemainWithOriginalPayoutWhileNewEarningsCanFundANewWithdrawal() {
		var f=earningFixture(Instant.now().minusSeconds(8*86400L),210_000);
		var d=payouts.saveDestination(f.specialistId(),new SavePayoutDestinationRequest("MOMO_WALLET","0900000710","Synthetic holder",null));
		var first=transactions.prepare(f.specialistId(),d.id(),"failed-lineage-command-01");
		transactions.reconcile(first.payoutId(),first.attemptId(),new PayoutProvider.Result("FAILED",null,"PAYOUT_PROVIDER_REJECTED"));
		assertThat(payouts.earnings(f.specialistId()).balance().availableVnd()).isZero();
		assertThat(payouts.earnings(f.specialistId()).balance().retryPendingVnd()).isEqualTo(210_000);
		jdbc.sql("update specialist_payout set requested_on=requested_on-1 where id=:id").param("id",first.payoutId()).update();
		var nextEarning=earningFixture(Instant.now().minusSeconds(8*86400L),70_000,f.specialistId());
		assertThatThrownBy(()->transactions.prepare(f.specialistId(),d.id(),"new-earnings-below-min-01"))
				.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PAYOUT_MINIMUM_NOT_REACHED"));
		earningFixture(Instant.now().minusSeconds(8*86400L),140_000,f.specialistId());
		var second=transactions.prepare(f.specialistId(),d.id(),"new-earnings-withdraw-01");
		assertThat(second.amountVnd()).isEqualTo(210_000);
		assertThat(jdbc.sql("select count(*) from specialist_payout_item where payout_id=:id").param("id",first.payoutId()).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from specialist_payout_item where payout_id=:id").param("id",second.payoutId()).query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("select count(*) from specialist_payout_item i join specialist_earning e on e.id=i.earning_id where i.payout_id=:id and e.consumed_credit_id=:credit")
				.param("id",second.payoutId()).param("credit",nextEarning.creditId()).query(Long.class).single()).isOne();
	}

	@Test
	void concurrentAdminRetriesReuseOneAttemptAndAuditAndConflictingReplayFails() throws Exception {
		var f=earningFixture(Instant.now().minusSeconds(8*86400L),210_000);
		var d=payouts.saveDestination(f.specialistId(),new SavePayoutDestinationRequest("MOMO_WALLET","0900000711","Synthetic holder",null));
		var first=transactions.prepare(f.specialistId(),d.id(),"concurrent-retry-source-01");
		transactions.reconcile(first.payoutId(),first.attemptId(),new PayoutProvider.Result("FAILED",null,"PAYOUT_INSUFFICIENT_BALANCE"));
		var adminId=UUID.randomUUID();
		var latch=new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.Callable<PayoutTransactions.PreparedPayout> task=()->{latch.await();return transactions.retry(adminId,first.payoutId(),first.attemptId(),"concurrent-admin-retry-01",UUID.randomUUID());};
		try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
			var a=executor.submit(task);var b=executor.submit(task);latch.countDown();
			var one=a.get(20,java.util.concurrent.TimeUnit.SECONDS);var two=b.get(20,java.util.concurrent.TimeUnit.SECONDS);
			assertThat(one.attemptId()).isEqualTo(two.attemptId());
			assertThat(one.submit() ^ two.submit()).isTrue();
		}
		assertThat(jdbc.sql("select count(*) from specialist_payout_attempt where payout_id=:id").param("id",first.payoutId()).query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("select count(*) from consultation_admin_audit_outbox where target_account_id=:id and event_type='consultation.payout.retried'")
				.param("id",f.specialistId()).query(Long.class).single()).isOne();
		assertThatThrownBy(()->transactions.retry(adminId,first.payoutId(),UUID.randomUUID(),"concurrent-admin-retry-01",UUID.randomUUID()))
				.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));
	}

	@Test
	void explicitRetryHttpIsAdminOnlyReplaysWithoutDispatchAndRejectsChangedDestination() throws Exception {
		var f=earningFixture(Instant.now().minusSeconds(8*86400L),210_000);
		var d=payouts.saveDestination(f.specialistId(),new SavePayoutDestinationRequest("MOMO_WALLET","0900000712","Synthetic holder",null));
		var first=transactions.prepare(f.specialistId(),d.id(),"retry-http-source-key-01");
		transactions.reconcile(first.payoutId(),first.attemptId(),new PayoutProvider.Result("FAILED",null,"PAYOUT_PROVIDER_REJECTED"));
		var body="{\"expectedAttemptId\":\"%s\"}".formatted(first.attemptId());
		var path="/api/v1/admin/payouts/"+first.payoutId()+"/retry";
		mvc.perform(post(path).with(specialist(f.specialistId())).header("Idempotency-Key","retry-http-command-key-01").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
		var actor=UUID.randomUUID();
		mvc.perform(post(path).with(admin(actor)).header("Idempotency-Key","retry-http-command-key-01").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store")).andExpect(jsonPath("$.payout.status").value("SUCCEEDED")).andExpect(jsonPath("$.attempts.length()").value(2));
		mvc.perform(post(path).with(admin(actor)).header("Idempotency-Key","retry-http-command-key-01").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andExpect(jsonPath("$.attempts.length()").value(2));
		assertThatThrownBy(()->transactions.prepare(f.specialistId(),UUID.randomUUID(),"retry-http-source-key-01"))
				.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));
	}

	@Test
	void payosPollLeasesFenceOldWorkersAndHorizonRetainsReservedFundsWithoutResubmit() throws Exception {
		var prepared=payosAttempt();
		makeDue(prepared.attemptId());
		var latch=new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.Callable<PayoutTransactions.Poll> claim=()->{latch.await();return transactions.claimPoll();};
		PayoutTransactions.Poll first;
		try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
			var one=executor.submit(claim);var two=executor.submit(claim);latch.countDown();
			var a=one.get(20,java.util.concurrent.TimeUnit.SECONDS);var b=two.get(20,java.util.concurrent.TimeUnit.SECONDS);
			assertThat((a==null) ^ (b==null)).isTrue();
			first=a==null?b:a;
		}
		assertThat(first.query().provider()).isEqualTo("PAYOS");
		assertThat(first.query().attemptId()).isEqualTo(prepared.attemptId());
		assertThat(first.query().requestId()).matches("[0-9a-f-]{36}");
		assertThat(transactions.claimPoll()).isNull();
		makeDue(prepared.attemptId());
		var second=transactions.claimPoll();
		transactions.finishPoll(first,new PayoutProvider.Result("SUCCEEDED","stale-outer",null,"stale-child"));
		assertThat(jdbc.sql("select status from specialist_payout where id=:id").param("id",prepared.payoutId()).query(String.class).single()).isEqualTo("PROCESSING");
		transactions.finishPoll(second,new PayoutProvider.Result("PROCESSING","matched-outer",null,"matched-child"));
		var schedule=jdbc.sql("select next_reconciliation_at,updated_at from specialist_payout_attempt where id=:id").param("id",prepared.attemptId()).query().singleRow();
		var gap=java.time.Duration.between(((java.sql.Timestamp)schedule.get("updated_at")).toInstant(),((java.sql.Timestamp)schedule.get("next_reconciliation_at")).toInstant());
		assertThat(gap).isEqualTo(java.time.Duration.ofSeconds(30));
		jdbc.sql("update specialist_payout_attempt set reconciliation_started_at=now()-interval '25 hours',reconciliation_deadline_at=now()-interval '1 hour',next_reconciliation_at=now()-interval '1 second' where id=:id")
				.param("id",prepared.attemptId()).update();
		assertThat(transactions.claimPoll().query()).isNull();
		var attempt=jdbc.sql("select status,reconciliation_status,failure_code,next_reconciliation_at,review_required_at from specialist_payout_attempt where id=:id").param("id",prepared.attemptId()).query().singleRow();
		assertThat(attempt).containsEntry("status","UNKNOWN").containsEntry("reconciliation_status","NEEDS_REVIEW")
				.containsEntry("failure_code","PAYOUT_RECONCILIATION_OVERDUE").containsEntry("next_reconciliation_at",null);
		assertThat(attempt.get("review_required_at")).isNotNull();
		assertThat(jdbc.sql("select count(*) from specialist_payout_item i join specialist_earning e on e.id=i.earning_id where i.payout_id=:id and e.status='PROCESSING'")
				.param("id",prepared.payoutId()).query(Long.class).single()).isOne();
		assertThatThrownBy(()->transactions.retry(UUID.randomUUID(),prepared.payoutId(),prepared.attemptId(),"unknown-cannot-retry-01",UUID.randomUUID()))
				.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PAYOUT_RETRY_NOT_AVAILABLE"));
		assertThat(transactions.claimPoll()).isNull();
	}

	@Test
	void matchedPollSuccessSettlesOnceAndSubsequentFailureCannotReopenPaidEarnings() {
		var p=payosAttempt();makeDue(p.attemptId());var poll=transactions.claimPoll();
		transactions.finishPoll(poll,new PayoutProvider.Result("SUCCEEDED","outer-success-"+p.attemptId(),null,"child-success-"+p.attemptId()));
		transactions.finishPoll(poll,new PayoutProvider.Result("FAILED",null,"PAYOUT_PROVIDER_REJECTED"));
		assertThat(jdbc.sql("select status from specialist_payout where id=:id").param("id",p.payoutId()).query(String.class).single()).isEqualTo("SUCCEEDED");
		assertThat(jdbc.sql("select count(*) from specialist_earning where id in(select earning_id from specialist_payout_item where payout_id=:id) and status='PAID'")
				.param("id",p.payoutId()).query(Long.class).single()).isOne();
	}

	@Test
	void closingFailedBankReleasesFundsForCorrectedDestinationAndRetainsHistory() throws Exception {
		var f=earningFixture(Instant.now().minusSeconds(8*86400L),210_000);
		var original=payouts.saveDestination(f.specialistId(),new SavePayoutDestinationRequest("BANK_ACCOUNT","1234567890","SYNTHETIC HOLDER","970436"));
		var first=transactions.prepare(f.specialistId(),original.id(),"close-bank-source-command-01");
		transactions.reconcile(first.payoutId(),first.attemptId(),new PayoutProvider.Result("FAILED",null,"PAYOUT_PROVIDER_REJECTED"));
		var corrected=payouts.saveDestination(f.specialistId(),new SavePayoutDestinationRequest("BANK_ACCOUNT","1234567891","SYNTHETIC HOLDER","970436"));
		assertThatThrownBy(()->transactions.retry(UUID.randomUUID(),first.payoutId(),first.attemptId(),"close-bank-retry-command-01",UUID.randomUUID()))
				.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PAYOUT_RETRY_NOT_AVAILABLE"));
		var path="/api/v1/admin/payouts/"+first.payoutId()+"/close-failed";
		var body="{\"expectedAttemptId\":\"%s\",\"reasonCode\":\"DESTINATION_UNUSABLE\"}".formatted(first.attemptId());
		mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
		mvc.perform(post(path).with(specialist(f.specialistId())).header("Idempotency-Key","close-bank-http-command-01").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
		mvc.perform(post(path).with(jwt().jwt(j->j.subject(UUID.randomUUID().toString())).authorities(new SimpleGrantedAuthority("ROLE_USER"))).header("Idempotency-Key","close-bank-http-command-01").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
		var actor=UUID.randomUUID();
		for(int i=0;i<2;i++) mvc.perform(post(path).with(admin(actor)).header("Idempotency-Key","close-bank-http-command-01").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store")).andExpect(jsonPath("$.payout.status").value("FAILED"))
				.andExpect(jsonPath("$.payout.closedAt").isString()).andExpect(jsonPath("$.payout.earningCount").value(1))
				.andExpect(jsonPath("$.attempts.length()").value(1));
		assertThat(jdbc.sql("select destination_id from specialist_payout where id=:id").param("id",first.payoutId()).query(UUID.class).single()).isEqualTo(original.id());
		assertThat(jdbc.sql("select count(*) from specialist_payout_item where payout_id=:id and released_at is not null").param("id",first.payoutId()).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from consultation_admin_audit_outbox where target_account_id=:id and event_type='consultation.payout.closed'").param("id",f.specialistId()).query(Long.class).single()).isOne();
		var view=payouts.earnings(f.specialistId());
		assertThat(view.balance().availableVnd()).isEqualTo(210_000);
		assertThat(view.balance().retryPendingVnd()).isZero();
		assertThat(view.payouts().getFirst().closedAt()).isNotNull();
		assertThatThrownBy(()->transactions.prepare(f.specialistId(),corrected.id(),"close-bank-same-day-command-01"))
				.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PAYOUT_DAILY_LIMIT_REACHED"));
		jdbc.sql("update specialist_payout set requested_on=requested_on-1 where id=:id").param("id",first.payoutId()).update();
		var next=transactions.prepare(f.specialistId(),corrected.id(),"close-bank-next-day-command-01");
		assertThat(next.amountVnd()).isEqualTo(210_000);
		assertThat(jdbc.sql("select count(*) from specialist_payout_item where payout_id=:id and released_at is null").param("id",next.payoutId()).query(Long.class).single()).isOne();
		assertThatThrownBy(()->jdbc.sql("update specialist_payout_item set released_at=null where payout_id=:id").param("id",first.payoutId()).update())
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		transactions.reconcile(first.payoutId(),first.attemptId(),new PayoutProvider.Result("SUCCEEDED","late-old-evidence",null));
		assertThat(jdbc.sql("select status from specialist_payout where id=:id").param("id",first.payoutId()).query(String.class).single()).isEqualTo("FAILED");
		transactions.reconcile(next.payoutId(),next.attemptId(),new PayoutProvider.Result("SUCCEEDED","new-corrected-evidence",null));
		assertThat(payouts.earnings(f.specialistId()).balance().paidVnd()).isEqualTo(210_000);
		assertThat(jdbc.sql("select count(*) from specialist_payout_item where earning_id in(select earning_id from specialist_payout_item where payout_id=:id)").param("id",first.payoutId()).query(Long.class).single()).isEqualTo(2);
		assertThat(transactions.prepare(f.specialistId(),original.id(),"close-bank-source-command-01").submit()).isFalse();
	}

	@Test
	void closeRejectsUncertainPaidStaleAndInvalidCommands() throws Exception {
		var f=earningFixture(Instant.now().minusSeconds(8*86400L),210_000);
		var d=payouts.saveDestination(f.specialistId(),new SavePayoutDestinationRequest("MOMO_WALLET","0900000791","Synthetic holder",null));
		var p=transactions.prepare(f.specialistId(),d.id(),"close-invalid-source-command-01");
		var actor=UUID.randomUUID();
		assertThatThrownBy(()->transactions.closeFailed(actor,p.payoutId(),p.attemptId(),"close-active-command-01",UUID.randomUUID()))
				.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PAYOUT_CLOSE_NOT_AVAILABLE"));
		transactions.reconcile(p.payoutId(),p.attemptId(),new PayoutProvider.Result("UNKNOWN",null,"PAYOUT_SUBMISSION_UNKNOWN"));
		assertThatThrownBy(()->transactions.closeFailed(actor,p.payoutId(),p.attemptId(),"close-unknown-command-01",UUID.randomUUID()))
				.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PAYOUT_CLOSE_NOT_AVAILABLE"));
		transactions.reconcile(p.payoutId(),p.attemptId(),new PayoutProvider.Result("SUCCEEDED","close-reject-paid-evidence",null));
		assertThatThrownBy(()->transactions.closeFailed(actor,p.payoutId(),p.attemptId(),"close-paid-command-01",UUID.randomUUID()))
				.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PAYOUT_CLOSE_NOT_AVAILABLE"));
		var path="/api/v1/admin/payouts/"+p.payoutId()+"/close-failed";
		mvc.perform(post(path).with(admin(actor)).header("Idempotency-Key","close-invalid-body-01").contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedAttemptId\":\"%s\",\"reasonCode\":\"OTHER\"}".formatted(p.attemptId()))).andExpect(status().isBadRequest());
		mvc.perform(post(path).with(admin(actor)).header("Idempotency-Key","close-missing-body-01").contentType(MediaType.APPLICATION_JSON)
				.content("{}")).andExpect(status().isBadRequest());
		assertThatThrownBy(()->transactions.closeFailed(actor,UUID.randomUUID(),p.attemptId(),"close-missing-payout-01",UUID.randomUUID()))
				.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PAYOUT_NOT_FOUND"));
	}

	@Test
	void concurrentCloseDeduplicatesAndRetryCannotReclaimReleasedItems() throws Exception {
		var f=earningFixture(Instant.now().minusSeconds(8*86400L),210_000);
		var d=payouts.saveDestination(f.specialistId(),new SavePayoutDestinationRequest("MOMO_WALLET","0900000792","Synthetic holder",null));
		var p=transactions.prepare(f.specialistId(),d.id(),"close-concurrent-source-01");
		transactions.reconcile(p.payoutId(),p.attemptId(),new PayoutProvider.Result("FAILED",null,"PAYOUT_PROVIDER_REJECTED"));
		var actor=UUID.randomUUID();
		assertThatThrownBy(()->transactions.closeFailed(actor,p.payoutId(),UUID.randomUUID(),"close-stale-attempt-01",UUID.randomUUID()))
				.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PAYOUT_CLOSE_NOT_AVAILABLE"));
		var latch=new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.Callable<Boolean> action=()->{latch.await();transactions.closeFailed(actor,p.payoutId(),p.attemptId(),"close-concurrent-key-01",UUID.randomUUID());return true;};
		try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
			var one=executor.submit(action);var two=executor.submit(action);latch.countDown();
			assertThat(one.get(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
			assertThat(two.get(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		}
		assertThat(jdbc.sql("select count(*) from payout_close_command where payout_id=:id").param("id",p.payoutId()).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from consultation_admin_audit_outbox where target_account_id=:id and event_type='consultation.payout.closed'").param("id",f.specialistId()).query(Long.class).single()).isOne();
		assertThatThrownBy(()->transactions.closeFailed(actor,p.payoutId(),UUID.randomUUID(),"close-concurrent-key-01",UUID.randomUUID()))
				.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));
		assertThatThrownBy(()->transactions.closeFailed(actor,p.payoutId(),p.attemptId(),"close-again-new-key-01",UUID.randomUUID()))
				.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PAYOUT_CLOSE_NOT_AVAILABLE"));
		assertThatThrownBy(()->transactions.retry(actor,p.payoutId(),p.attemptId(),"retry-closed-payout-01",UUID.randomUUID()))
				.isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PAYOUT_RETRY_NOT_AVAILABLE"));
	}

	@Test
	void payosPollingPersistsEveryBackoffWithoutCreatingAnotherAttempt() {
		var p=payosAttempt();
		for(long seconds:new long[]{15,30,60,120,300,300}) {
			makeDue(p.attemptId());var poll=transactions.claimPoll();
			assertThat(poll.query().attemptId()).isEqualTo(p.attemptId());
			transactions.finishPoll(poll,new PayoutProvider.Result("PROCESSING",null,null));
			var row=jdbc.sql("select next_reconciliation_at,updated_at from specialist_payout_attempt where id=:id").param("id",p.attemptId()).query().singleRow();
			assertThat(java.time.Duration.between(((java.sql.Timestamp)row.get("updated_at")).toInstant(),((java.sql.Timestamp)row.get("next_reconciliation_at")).toInstant()).toSeconds()).isEqualTo(seconds);
		}
		assertThat(jdbc.sql("select count(*) from specialist_payout_attempt where payout_id=:id").param("id",p.payoutId()).query(Long.class).single()).isOne();
		transactions.reconcile(p.payoutId(),p.attemptId(),new PayoutProvider.Result("FAILED",null,"PAYOUT_PROVIDER_REJECTED"));
	}

	private PayoutTransactions.PreparedPayout payosAttempt() {
		var f=earningFixture(Instant.now().minusSeconds(8*86400L),210_000);
		var d=payouts.saveDestination(f.specialistId(),new SavePayoutDestinationRequest("BANK_ACCOUNT","1234567890","SYNTHETIC HOLDER","970436"));
		var p=transactions.prepare(f.specialistId(),d.id(),"poll-source-"+UUID.randomUUID());
		jdbc.sql("update specialist_payout_destination set payout_provider='PAYOS',confirmed_by=:admin where id=:id").param("admin",UUID.randomUUID()).param("id",d.id()).update();
		jdbc.sql("with p as(update specialist_payout set payout_provider='PAYOS' where id=:p returning id) update specialist_payout_attempt set payout_provider='PAYOS',provider_idempotency_key=:key where id=:a")
				.param("p",p.payoutId()).param("key",UUID.randomUUID().toString()).param("a",p.attemptId()).update();
		return p;
	}

	private void makeDue(UUID attemptId) {
		jdbc.sql("update specialist_payout_attempt set next_reconciliation_at=now()-interval '1 second',reconciliation_lease_until=null where id=:id").param("id",attemptId).update();
	}

	private MomoPayoutIpnRequest momoCallback(PayoutTransactions.PreparedPayout payout, String requestId,
			long amount) {
		return new MomoPayoutIpnRequest("MOMO_SANDBOX", payout.payoutId().toString(), requestId, amount,
				0, 123456789L, Instant.now().toEpochMilli(), "Successful.", "Specialist payout",
				"disburseToWallet", "", "test-signature");
	}

	private Fixture earningFixture(Instant earnedAt, long amount) {
		return earningFixture(earnedAt, amount, UUID.randomUUID());
	}

	private Fixture earningFixture(Instant earnedAt, long amount, UUID specialistId) {
		var allocation = Math.multiplyExact(amount, 10_000) / 7_000;
		var platformAllocation = allocation - amount;
		var userId = UUID.randomUUID();
		var periodId = UUID.randomUUID();
		var creditId = UUID.randomUUID();
		var slotId = UUID.randomUUID();
		var appointmentId = UUID.randomUUID();
		var completionFactId = UUID.randomUUID();
		var start = jdbc.sql("select end_at from availability_slot where specialist_account_id=:id order by end_at desc limit 1")
				.param("id",specialistId).query(Timestamp.class).optional().map(end -> end.toInstant().plusSeconds(3600))
				.orElseGet(() -> Instant.now().plusSeconds(86_400));
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Payout specialist', 'Payout test profile', 5, 'Asia/Ho_Chi_Minh',
				 'APPROVED', now(), now(), :admin) on conflict(account_id) do nothing
				""").param("id", specialistId).param("admin", UUID.randomUUID()).update();
		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_allocation_minor, created_at, updated_at
				) values (:id, :userId, 'service-entitlement-v1', 'consultation-credit-v2', 'PLUS', 'PAID',
				 :reference, now() - interval '1 day', now() + interval '29 days', 4, :allocation, now(), now())
				""").param("id", periodId).param("userId", userId).param("reference", "payout-fixture-" + periodId)
				.param("allocation", allocation).update();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, appointment_id, created_at, updated_at)
				values (:id, :periodId, 1, 'HELD', :appointmentId, now(), now())
				""").param("id", creditId).param("periodId", periodId).param("appointmentId", appointmentId).update();
		jdbc.sql("""
				insert into availability_slot (
				 id, specialist_account_id, start_at, end_at, timezone, modality, idempotency_key, created_at, updated_at
				) values (:id, :specialistId, :startAt, :endAt, 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT', :key, now(), now())
				""").param("id", slotId).param("specialistId", specialistId).param("startAt", Timestamp.from(start))
				.param("endAt", Timestamp.from(start.plusSeconds(3600))).param("key", "payout-slot-key-" + slotId).update();
		jdbc.sql("""
				insert into appointment (
				 id, user_account_id, specialist_account_id, availability_slot_id, service_credit_id,
				 status, modality, scheduled_start_at, scheduled_end_at, display_timezone,
				 requested_at, decision_deadline_at, idempotency_key, created_at, updated_at
				) values (:id, :userId, :specialistId, :slotId, :creditId, 'REQUESTED', 'IN_APP_CHAT',
				 :startAt, :endAt, 'Asia/Ho_Chi_Minh', now(), :deadline, :key, now(), now())
				""").param("id", appointmentId).param("userId", userId).param("specialistId", specialistId)
				.param("slotId", slotId).param("creditId", creditId).param("startAt", Timestamp.from(start))
				.param("endAt", Timestamp.from(start.plusSeconds(3600))).param("deadline", Timestamp.from(start.minusSeconds(3600)))
				.param("key", "payout-appointment-" + appointmentId).update();
		jdbc.sql("""
				update appointment set status='COMPLETED', decided_at=:earnedAt, decision_reason='SPECIALIST_ACCEPTED',
				 session_outcome='COMPLETED',
				 session_outcome_reason='EVIDENCE_REQUIREMENTS_MET', session_policy_version='chat-session-completion-v1',
				 session_ended_at=:earnedAt, session_settled_at=:earnedAt, completion_fact_id=:fact where id=:id
				""").param("id", appointmentId).param("fact", completionFactId)
				.param("earnedAt", Timestamp.from(earnedAt)).update();
		jdbc.sql("update service_credit set state='CONSUMED' where id=:id").param("id", creditId).update();
		jdbc.sql("""
				insert into specialist_earning (
				 id, appointment_id, completion_fact_id, consumed_credit_id, specialist_account_id,
				 plan_version, currency, credit_allocation_minor, specialist_share_bps,
				 specialist_amount_minor, platform_allocation_minor, idempotency_source,
				 status, earned_at, settlement_available_at, created_at, updated_at
				) values (:id, :appointmentId, :completionFactId, :creditId, :specialistId,
				 'service-entitlement-v1', 'VND', :allocation, 7000, :amount, :platformAllocation, :source,
				 'PENDING_SETTLEMENT', :earnedAt, :availableAt, now(), now())
				""").param("id", UUID.randomUUID()).param("appointmentId", appointmentId)
				.param("completionFactId", completionFactId).param("creditId", creditId)
				.param("specialistId", specialistId).param("amount", amount)
				.param("allocation", allocation).param("platformAllocation", platformAllocation)
				.param("source", "test-completion:" + completionFactId).param("earnedAt", Timestamp.from(earnedAt))
				.param("availableAt", Timestamp.from(earnedAt.plusSeconds(7 * 86_400L))).update();
		return new Fixture(specialistId, creditId);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor specialist(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", List.of("SPECIALIST")))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", List.of("USER")))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor admin(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", List.of("ADMIN")))
				.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private record Fixture(UUID specialistId, UUID creditId) {
	}
}

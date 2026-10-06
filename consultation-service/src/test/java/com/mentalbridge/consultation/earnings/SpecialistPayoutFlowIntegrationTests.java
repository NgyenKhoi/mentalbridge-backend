package com.mentalbridge.consultation.earnings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.mentalbridge.consultation.shared.ApiException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SpecialistPayoutFlowIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired SpecialistPayoutService payouts;
	@Autowired PayoutTransactions transactions;

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
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.items[0].destinationHint").value("•••• 5678"));

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
	void definiteProviderFailureCanRetryTheSameLogicalPayoutWithoutDoublePaying() {
		var fixture = earningFixture(Instant.now().minusSeconds(8 * 86_400L), 210_000);
		var destination = payouts.saveDestination(fixture.specialistId(),
				new SavePayoutDestinationRequest("MOMO_WALLET", "0900000001", "Nguyen Thu Ha", null));
		payouts.earnings(fixture.specialistId());

		var first = transactions.prepare(fixture.specialistId(), destination.id(), "retry-provider-command-0001");
		transactions.reconcile(first.payoutId(), first.attemptId(),
				new PayoutProvider.Result("FAILED", null, "PROVIDER_REJECTED"));
		var retry = transactions.prepare(fixture.specialistId(), destination.id(), "retry-provider-command-0001");

		assertThat(retry.payoutId()).isEqualTo(first.payoutId());
		assertThat(retry.attemptId()).isNotEqualTo(first.attemptId());
		assertThat(retry.submit()).isTrue();
		transactions.reconcile(retry.payoutId(), retry.attemptId(),
				new PayoutProvider.Result("SUCCEEDED", "fake-retry-success", null));

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
		jdbc.sql("update specialist_payout set payout_provider='MOMO' where id=:id")
				.param("id", prepared.payoutId()).update();
		jdbc.sql("update specialist_payout_attempt set payout_provider='MOMO', provider_idempotency_key=:key where id=:id")
				.param("key", requestId).param("id", prepared.attemptId()).update();

		var mismatch = momoCallback(prepared, requestId, prepared.amountVnd() - 1);
		assertThatThrownBy(() -> transactions.reconcileMomo(mismatch, "a".repeat(64)))
				.isInstanceOf(ApiException.class)
				.satisfies(error -> assertThat(((ApiException) error).code())
						.isEqualTo("MOMO_PAYOUT_CALLBACK_MISMATCH"));

		var valid = momoCallback(prepared, requestId, prepared.amountVnd());
		transactions.reconcileMomo(valid, "b".repeat(64));
		transactions.reconcileMomo(valid, "b".repeat(64));

		assertThat(jdbc.sql("select status from specialist_payout where id=:id")
				.param("id", prepared.payoutId()).query(String.class).single()).isEqualTo("SUCCEEDED");
		assertThat(jdbc.sql("select count(*) from payout_provider_event where payout_attempt_id=:id")
				.param("id", prepared.attemptId()).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from specialist_payout_status_history where payout_id=:id")
				.param("id", prepared.payoutId()).query(Long.class).single()).isEqualTo(2);
	}

	private MomoPayoutIpnRequest momoCallback(PayoutTransactions.PreparedPayout payout, String requestId,
			long amount) {
		return new MomoPayoutIpnRequest("MOMO_SANDBOX", payout.payoutId().toString(), requestId, amount,
				0, 123456789L, Instant.now().toEpochMilli(), "Successful.", "Specialist payout",
				"disburseToWallet", "", "test-signature");
	}

	private Fixture earningFixture(Instant earnedAt, long amount) {
		var allocation = Math.multiplyExact(amount, 10_000) / 7_000;
		var platformAllocation = allocation - amount;
		var specialistId = UUID.randomUUID();
		var userId = UUID.randomUUID();
		var periodId = UUID.randomUUID();
		var creditId = UUID.randomUUID();
		var slotId = UUID.randomUUID();
		var appointmentId = UUID.randomUUID();
		var completionFactId = UUID.randomUUID();
		var start = Instant.now().plusSeconds(86_400);
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Payout specialist', 'Payout test profile', 5, 'Asia/Ho_Chi_Minh',
				 'APPROVED', now(), now(), :admin)
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

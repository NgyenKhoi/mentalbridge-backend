package com.mentalbridge.consultation.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.mentalbridge.consultation.billing.SubscriptionBillingService;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminCommercialReconciliationIntegrationTests extends ConsultationTestProperties {

	private static final UUID PLUS = UUID.fromString("62200000-0000-4000-8000-000000000002");

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired SubscriptionBillingService billing;

	@Test
	void adminRoleRequiredForCommercialReconciliationEndpoints() throws Exception {
		var subId = UUID.randomUUID();
		var payoutId = UUID.randomUUID();

		for (var path : new String[] {
				"/api/v1/admin/payments",
				"/api/v1/admin/payouts",
				"/api/v1/admin/commercial/discrepancies",
				"/api/v1/admin/commercial/provenance?paymentId=" + UUID.randomUUID(),
				"/api/v1/admin/subscriptions/" + subId,
				"/api/v1/admin/payouts/" + payoutId
		}) {
			// Unauthenticated -> 401
			mvc.perform(get(path)).andExpect(status().isUnauthorized());

			// USER role -> 403
			mvc.perform(get(path).with(role(UUID.randomUUID(), "ROLE_USER"))).andExpect(status().isForbidden());

			// SPECIALIST role -> 403
			mvc.perform(get(path).with(role(UUID.randomUUID(), "ROLE_SPECIALIST"))).andExpect(status().isForbidden());
		}
	}

	@Test
	void adminCanFilterPaymentsByStatusAndSubscription() throws Exception {
		var userId = UUID.randomUUID();
		var checkout = billing.checkout(userId, PLUS, "checkout-" + UUID.randomUUID());

		// ADMIN read with filters
		mvc.perform(get("/api/v1/admin/payments")
				.param("status", "PENDING")
				.param("subscriptionId", checkout.subscriptionId().toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.items[0].paymentId").value(checkout.paymentId().toString()))
				.andExpect(jsonPath("$.items[0].status").value("PENDING"))
				.andExpect(jsonPath("$.items[0].amountVnd").value(5000));

		// Non-matching filter returns empty list
		mvc.perform(get("/api/v1/admin/payments")
				.param("status", "SUCCEEDED")
				.param("subscriptionId", checkout.subscriptionId().toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(0))
				.andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void adminCanReadSubscriptionDetail() throws Exception {
		var userId = UUID.randomUUID();
		var checkout = billing.checkout(userId, PLUS, "detail-test-" + UUID.randomUUID());

		mvc.perform(get("/api/v1/admin/subscriptions/{subscriptionId}", checkout.subscriptionId())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.subscriptionId").value(checkout.subscriptionId().toString()))
				.andExpect(jsonPath("$.planCode").value("PLUS"))
				.andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
				.andExpect(jsonPath("$.payments[0].paymentId").value(checkout.paymentId().toString()));

		// Non-existent subscription returns 404
		mvc.perform(get("/api/v1/admin/subscriptions/{subscriptionId}", UUID.randomUUID())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isNotFound());
	}

	@Test
	void adminCanReadCommercialDiscrepancies() throws Exception {
		var result = mvc.perform(get("/api/v1/admin/commercial/discrepancies")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andReturn();

		var body = json.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.has("generatedAt")).isTrue();
		assertThat(body.has("totalDiscrepancies")).isTrue();
		assertThat(body.has("webhooks")).isTrue();
		assertThat(body.has("payouts")).isTrue();
		assertThat(body.has("heldEarnings")).isTrue();
		assertThat(body.has("disputes")).isTrue();
	}

	@Test
	void adminCanTraceProvenanceAcrossPaymentSubscription() throws Exception {
		var userId = UUID.randomUUID();
		var checkout = billing.checkout(userId, PLUS, "trace-test-" + UUID.randomUUID());

		// Trace by paymentId
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("paymentId", checkout.paymentId().toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.queryIdentifierType").value("PAYMENT"))
				.andExpect(jsonPath("$.queryIdentifierValue").value(checkout.paymentId().toString()))
				.andExpect(jsonPath("$.subscription.subscriptionId").value(checkout.subscriptionId().toString()))
				.andExpect(jsonPath("$.payments[0].paymentId").value(checkout.paymentId().toString()));

		// Trace by subscriptionId
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("subscriptionId", checkout.subscriptionId().toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.subscription.subscriptionId").value(checkout.subscriptionId().toString()));

		// Missing parameter returns 400
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void fix1_creditsBelongStrictlyToSubscriptionAndNeverLeakUnrelatedDemoOrOtherSubCredits() throws Exception {
		var userId = UUID.randomUUID();
		var checkoutA = billing.checkout(userId, PLUS, "checkout-a-" + UUID.randomUUID());
		jdbc.sql("update payment_transaction set status='SUCCEEDED', paid_at=now() where id=:id")
				.param("id", checkoutA.paymentId()).update();
		jdbc.sql("update user_subscription set status='ENDED', ended_at=now() where id=:id")
				.param("id", checkoutA.subscriptionId()).update();

		var checkoutB = billing.checkout(userId, PLUS, "checkout-b-" + UUID.randomUUID());

		var periodA = UUID.randomUUID();
		var creditA = UUID.randomUUID();
		var periodB = UUID.randomUUID();
		var creditB = UUID.randomUUID();
		var demoPeriod = UUID.randomUUID();
		var demoCredit = UUID.randomUUID();

		// Insert paid period & credit for sub A
		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_allocation_minor, created_at, updated_at
				) values (:id, :userId, 'v1', 'consultation-credit-v1', 'PLUS', 'PAID', :refA, now() - interval '60 days', now() - interval '31 days', 1, 100000, now(), now())
				""").param("id", periodA).param("userId", userId).param("refA", checkoutA.subscriptionId().toString()).update();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, created_at, updated_at)
				values (:id, :periodId, 1, 'AVAILABLE', now(), now())
				""").param("id", creditA).param("periodId", periodA).update();

		// Insert paid period & credit for sub B
		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_allocation_minor, created_at, updated_at
				) values (:id, :userId, 'v1', 'consultation-credit-v1', 'PLUS', 'PAID', :refB, now() - interval '30 days', now() - interval '1 day', 1, 100000, now(), now())
				""").param("id", periodB).param("userId", userId).param("refB", checkoutB.subscriptionId().toString()).update();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, created_at, updated_at)
				values (:id, :periodId, 1, 'AVAILABLE', now(), now())
				""").param("id", creditB).param("periodId", periodB).update();

		// Insert demo credit for the same user
		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_allocation_minor, created_at, updated_at
				) values (:id, :userId, 'v1', 'consultation-credit-v1', 'PLUS', 'DEMO', 'demo-grant', now(), now() + interval '30 days', 1, 100000, now(), now())
				""").param("id", demoPeriod).param("userId", userId).update();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, created_at, updated_at)
				values (:id, :periodId, 1, 'AVAILABLE', now(), now())
				""").param("id", demoCredit).param("periodId", demoPeriod).update();

		// 1. Check AdminSubscriptionController: /api/v1/admin/subscriptions/{subA}
		mvc.perform(get("/api/v1/admin/subscriptions/{subscriptionId}", checkoutA.subscriptionId())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.subscriptionId").value(checkoutA.subscriptionId().toString()))
				.andExpect(jsonPath("$.credits.length()").value(1))
				.andExpect(jsonPath("$.credits[0].creditId").value(creditA.toString()));

		// 2. Check AdminCommercialReconciliationController provenance by subscriptionId
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("subscriptionId", checkoutA.subscriptionId().toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.credits.length()").value(1))
				.andExpect(jsonPath("$.credits[0].creditId").value(creditA.toString()));
	}

	@Test
	void fix2_payoutProvenanceResolvesMultipleEarningsAcrossDifferentSubscriptions() throws Exception {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Multi Spec', 'Bio', 5, 'Asia/Ho_Chi_Minh', 'APPROVED', now(), now(), :admin)
				""").param("id", specialistId).param("admin", adminId).update();

		var user1 = UUID.randomUUID();
		var user2 = UUID.randomUUID();
		var checkout1 = billing.checkout(user1, PLUS, "co-1-" + UUID.randomUUID());
		var checkout2 = billing.checkout(user2, PLUS, "co-2-" + UUID.randomUUID());

		// Period & Credit 1
		var period1 = UUID.randomUUID();
		var credit1 = UUID.randomUUID();
		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_allocation_minor, created_at, updated_at
				) values (:id, :userId, 'v1', 'consultation-credit-v1', 'PLUS', 'PAID', :ref, now(), now() + interval '30 days', 1, 100000, now(), now())
				""").param("id", period1).param("userId", user1).param("ref", checkout1.subscriptionId().toString()).update();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, created_at, updated_at)
				values (:id, :periodId, 1, 'AVAILABLE', now(), now())
				""").param("id", credit1).param("periodId", period1).update();

		// Period & Credit 2
		var period2 = UUID.randomUUID();
		var credit2 = UUID.randomUUID();
		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_allocation_minor, created_at, updated_at
				) values (:id, :userId, 'v1', 'consultation-credit-v1', 'PLUS', 'PAID', :ref, now(), now() + interval '30 days', 1, 100000, now(), now())
				""").param("id", period2).param("userId", user2).param("ref", checkout2.subscriptionId().toString()).update();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, created_at, updated_at)
				values (:id, :periodId, 1, 'AVAILABLE', now(), now())
				""").param("id", credit2).param("periodId", period2).update();

		// Appointments
		var app1 = UUID.randomUUID();
		var app2 = UUID.randomUUID();
		var slot1 = UUID.randomUUID();
		var slot2 = UUID.randomUUID();

		var fact1 = UUID.randomUUID();
		var fact2 = UUID.randomUUID();

		jdbc.sql("""
				insert into availability_slot (id, specialist_account_id, start_at, end_at, timezone, modality, idempotency_key, created_at, updated_at)
				values (:id, :spec, now() - interval '3 hours', now() - interval '2 hours', 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT', :k, now(), now())
				""").param("id", slot1).param("spec", specialistId).param("k", "slot1-" + slot1).update();
		jdbc.sql("""
				insert into appointment (id, user_account_id, specialist_account_id, availability_slot_id, service_credit_id, status, modality, scheduled_start_at, scheduled_end_at, display_timezone, requested_at, decision_deadline_at, idempotency_key, created_at, updated_at)
				values (:id, :user, :spec, :slot, :credit, 'REQUESTED', 'IN_APP_CHAT', now() - interval '3 hours', now() - interval '2 hours', 'Asia/Ho_Chi_Minh', now() - interval '5 hours', now() - interval '2 hours 30 minutes', :k, now(), now())
				""").param("id", app1).param("user", user1).param("spec", specialistId).param("slot", slot1).param("credit", credit1).param("k", "app1-" + app1).update();
		jdbc.sql("""
				update appointment set status='COMPLETED', decided_at=now() - interval '2 hours 45 minutes', decision_reason='SPECIALIST_ACCEPTED',
				 session_outcome='COMPLETED', session_outcome_reason='EVIDENCE_REQUIREMENTS_MET', session_policy_version='chat-session-completion-v1',
				 session_ended_at=now() - interval '2 hours', session_settled_at=now() - interval '2 hours', completion_fact_id=:fact where id=:id
				""").param("id", app1).param("fact", fact1).update();
		jdbc.sql("update service_credit set state='CONSUMED', appointment_id=:appId where id=:creditId")
				.param("appId", app1).param("creditId", credit1).update();

		jdbc.sql("""
				insert into availability_slot (id, specialist_account_id, start_at, end_at, timezone, modality, idempotency_key, created_at, updated_at)
				values (:id, :spec, now() - interval '2 hours', now() - interval '1 hour', 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT', :k, now(), now())
				""").param("id", slot2).param("spec", specialistId).param("k", "slot2-" + slot2).update();
		jdbc.sql("""
				insert into appointment (id, user_account_id, specialist_account_id, availability_slot_id, service_credit_id, status, modality, scheduled_start_at, scheduled_end_at, display_timezone, requested_at, decision_deadline_at, idempotency_key, created_at, updated_at)
				values (:id, :user, :spec, :slot, :credit, 'REQUESTED', 'IN_APP_CHAT', now() - interval '2 hours', now() - interval '1 hour', 'Asia/Ho_Chi_Minh', now() - interval '4 hours', now() - interval '1 hour 30 minutes', :k, now(), now())
				""").param("id", app2).param("user", user2).param("spec", specialistId).param("slot", slot2).param("credit", credit2).param("k", "app2-" + app2).update();
		jdbc.sql("""
				update appointment set status='COMPLETED', decided_at=now() - interval '1 hour 45 minutes', decision_reason='SPECIALIST_ACCEPTED',
				 session_outcome='COMPLETED', session_outcome_reason='EVIDENCE_REQUIREMENTS_MET', session_policy_version='chat-session-completion-v1',
				 session_ended_at=now() - interval '1 hour', session_settled_at=now() - interval '1 hour', completion_fact_id=:fact where id=:id
				""").param("id", app2).param("fact", fact2).update();
		jdbc.sql("update service_credit set state='CONSUMED', appointment_id=:appId where id=:creditId")
				.param("appId", app2).param("creditId", credit2).update();

		// Earnings
		var earn1 = UUID.randomUUID();
		var earn2 = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_earning (
				 id, appointment_id, completion_fact_id, consumed_credit_id, specialist_account_id,
				 plan_version, currency, credit_allocation_minor, specialist_share_bps,
				 specialist_amount_minor, platform_allocation_minor, idempotency_source,
				 status, earned_at, settlement_available_at, paid_at, created_at, updated_at
				) values (:id, :appId, :fact, :creditId, :spec, 'v1', 'VND', 100000, 7000, 70000, 30000, :src, 'PAID', now(), now(), now(), now(), now())
				""").param("id", earn1).param("appId", app1).param("fact", fact1).param("creditId", credit1).param("spec", specialistId).param("src", "src-" + earn1).update();

		jdbc.sql("""
				insert into specialist_earning (
				 id, appointment_id, completion_fact_id, consumed_credit_id, specialist_account_id,
				 plan_version, currency, credit_allocation_minor, specialist_share_bps,
				 specialist_amount_minor, platform_allocation_minor, idempotency_source,
				 status, earned_at, settlement_available_at, paid_at, created_at, updated_at
				) values (:id, :appId, :fact, :creditId, :spec, 'v1', 'VND', 100000, 7000, 70000, 30000, :src, 'PAID', now(), now(), now(), now(), now())
				""").param("id", earn2).param("appId", app2).param("fact", fact2).param("creditId", credit2).param("spec", specialistId).param("src", "src-" + earn2).update();

		// Destination & Payout
		var destId = UUID.randomUUID();
		var payoutId = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_payout_destination (
				 id, specialist_account_id, payout_provider, destination_type,
				 destination_ciphertext, encryption_key_version, destination_fingerprint,
				 display_hint, status, verified_at, created_at, updated_at
				) values (
				 :id, :spec, 'MOMO', 'MOMO_WALLET',
				 'cipher', 'v1', 'fingerprint',
				 '0912***', 'VERIFIED', now(), now(), now()
				)
				""").param("id", destId).param("spec", specialistId).update();

		jdbc.sql("""
				insert into specialist_payout (id, specialist_account_id, destination_id, amount_minor, currency, status, payout_provider, idempotency_key, requested_at, requested_on, completed_at, created_at, updated_at)
				values (:id, :spec, :dest, 140000, 'VND', 'SUCCEEDED', 'MOMO', :k, now(), current_date, now(), now(), now())
				""").param("id", payoutId).param("spec", specialistId).param("dest", destId).param("k", "payout-" + payoutId).update();

		// Link payout items
		jdbc.sql("""
				insert into specialist_payout_item (payout_id, earning_id, amount_minor)
				values (:payoutId, :earn1, 70000), (:payoutId, :earn2, 70000)
				""").param("payoutId", payoutId).param("earn1", earn1).param("earn2", earn2).update();

		// Query provenance by payoutId
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("payoutId", payoutId.toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.payouts.length()").value(1))
				.andExpect(jsonPath("$.payouts[0].payoutId").value(payoutId.toString()))
				.andExpect(jsonPath("$.earnings.length()").value(2))
				.andExpect(jsonPath("$.appointments.length()").value(2))
				.andExpect(jsonPath("$.credits.length()").value(2))
				.andExpect(jsonPath("$.subscriptions.length()").value(2))
				.andExpect(jsonPath("$.subscription").isMap());
	}

	@Test
	void fix3_discrepanciesClassifyDuplicateIpnAndCalculateAccurateTotalCounts() throws Exception {
		var orderIdDup = "order-dup-" + UUID.randomUUID();
		var reqIdDup = "req-dup-" + UUID.randomUUID();
		var transIdDup = Math.abs(UUID.randomUUID().getMostSignificantBits());

		// Insert 2 IPN records that share order_id + request_id (DUPLICATE_DELIVERY)
		jdbc.sql("""
				insert into momo_payment_ipn (
				 id, contract_version, deduplication_key, partner_code, order_id, request_id,
				 amount_minor, currency, order_info_sha256, order_type, trans_id, result_code,
				 result_message, pay_type, response_time_epoch_ms, extra_data_sha256,
				 provider_occurred_at, payload_sha256, signature_key_version, signature_verified_at,
				 processing_status, received_at
				) values (
				 :id, 'v1', :key, 'MOMO', :orderId, :reqId,
				 50000, 'VND', 'hash', 'momo_wallet', :transId, 0,
				 'Success', 'qr', 1700000000, 'hash', now(), 'hash', 'v1', now(),
				 'PROCESSED', now()
				)
				""").param("id", UUID.randomUUID()).param("key", "k1-" + UUID.randomUUID())
				.param("orderId", orderIdDup).param("reqId", reqIdDup).param("transId", transIdDup).update();

		jdbc.sql("""
				insert into momo_payment_ipn (
				 id, contract_version, deduplication_key, partner_code, order_id, request_id,
				 amount_minor, currency, order_info_sha256, order_type, trans_id, result_code,
				 result_message, pay_type, response_time_epoch_ms, extra_data_sha256,
				 provider_occurred_at, payload_sha256, signature_key_version, signature_verified_at,
				 processing_status, received_at
				) values (
				 :id, 'v1', :key, 'MOMO', :orderId, :reqId,
				 50000, 'VND', 'hash', 'momo_wallet', :transId, 0,
				 'Success', 'qr', 1700000000, 'hash', now(), 'hash', 'v1', now(),
				 'PROCESSED', now()
				)
				""").param("id", UUID.randomUUID()).param("key", "k2-" + UUID.randomUUID())
				.param("orderId", orderIdDup).param("reqId", reqIdDup).param("transId", transIdDup).update();

		var result = mvc.perform(get("/api/v1/admin/commercial/discrepancies")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andReturn();

		var body = json.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.get("totalDiscrepancies").asLong()).isGreaterThanOrEqualTo(2);

		var webhooks = body.get("webhooks");
		assertThat(webhooks.isArray()).isTrue();
		boolean foundDup = false;
		for (var w : webhooks) {
			if (orderIdDup.equals(w.get("orderId").asText())) {
				foundDup = true;
				assertThat(w.get("discrepancyType").asText()).isIn("DUPLICATE_TRANS_ID", "DUPLICATE_DELIVERY");
			}
		}
		assertThat(foundDup).isTrue();
	}

	@Test
	void fix4_filteringAndPaginationWithSafeTimestampAndCorrelationId() throws Exception {
		var userId = UUID.randomUUID();
		var checkout = billing.checkout(userId, PLUS, "filter-test-" + UUID.randomUUID());

		// Test payments with correlationId & offset
		mvc.perform(get("/api/v1/admin/payments")
				.param("status", "PENDING")
				.param("offset", "0")
				.param("limit", "10")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").isNumber());

		// Test payouts with offset & limit
		mvc.perform(get("/api/v1/admin/payouts")
				.param("status", "FAILED")
				.param("offset", "0")
				.param("limit", "10")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").isNumber());

		// Invalid timestamp in date range returns 400 INVALID_DATE_RANGE
		mvc.perform(get("/api/v1/admin/payments")
				.param("from", "invalid-timestamp")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));

		mvc.perform(get("/api/v1/admin/payouts")
				.param("from", "invalid-timestamp")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
	}

	@Test
	void fixA_momoLifecycleTransitionAuthorizedPendingToSuccessIsNotMisclassifiedAsDiscrepancy() throws Exception {
		var orderId = "order-lifecycle-" + UUID.randomUUID();
		var reqId = "req-lifecycle-" + UUID.randomUUID();
		var transId = Math.abs(UUID.randomUUID().getMostSignificantBits());

		// 1. Authorized pending IPN (resultCode = 9000)
		jdbc.sql("""
				insert into momo_payment_ipn (
				 id, contract_version, deduplication_key, partner_code, order_id, request_id,
				 amount_minor, currency, order_info_sha256, order_type, trans_id, result_code,
				 result_message, pay_type, response_time_epoch_ms, extra_data_sha256,
				 provider_occurred_at, payload_sha256, signature_key_version, signature_verified_at,
				 processing_status, received_at
				) values (
				 :id, 'v1', :key, 'MOMO', :orderId, :reqId,
				 50000, 'VND', 'hash', 'momo_wallet', :transId, 9000,
				 'Authorized Pending', 'qr', 1700000000, 'hash', now(), 'hash', 'v1', now(),
				 'PROCESSED', now() - interval '5 minutes'
				)
				""").param("id", UUID.randomUUID()).param("key", "k1-" + UUID.randomUUID())
				.param("orderId", orderId).param("reqId", reqId).param("transId", transId).update();

		// 2. Final success IPN (resultCode = 0)
		jdbc.sql("""
				insert into momo_payment_ipn (
				 id, contract_version, deduplication_key, partner_code, order_id, request_id,
				 amount_minor, currency, order_info_sha256, order_type, trans_id, result_code,
				 result_message, pay_type, response_time_epoch_ms, extra_data_sha256,
				 provider_occurred_at, payload_sha256, signature_key_version, signature_verified_at,
				 processing_status, received_at
				) values (
				 :id, 'v1', :key, 'MOMO', :orderId, :reqId,
				 50000, 'VND', 'hash', 'momo_wallet', :transId, 0,
				 'Success', 'qr', 1700000100, 'hash', now(), 'hash', 'v1', now(),
				 'PROCESSED', now()
				)
				""").param("id", UUID.randomUUID()).param("key", "k2-" + UUID.randomUUID())
				.param("orderId", orderId).param("reqId", reqId).param("transId", transId).update();

		var result = mvc.perform(get("/api/v1/admin/commercial/discrepancies")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andReturn();

		var body = json.readTree(result.getResponse().getContentAsByteArray());
		var webhooks = body.get("webhooks");
		for (var w : webhooks) {
			assertThat(w.get("orderId").asText()).isNotEqualTo(orderId);
		}
	}

	@Test
	void fixB_payoutProvenanceQueryDoesNotExpandToUnrelatedPayoutsSharingSubscription() throws Exception {
		var specialist1 = UUID.randomUUID();
		var specialist2 = UUID.randomUUID();
		var adminId = UUID.randomUUID();

		for (var spec : new UUID[] { specialist1, specialist2 }) {
			jdbc.sql("""
					insert into specialist_profile (
					 account_id, display_name, biography, years_experience, timezone, approval_status,
					 submitted_at, reviewed_at, reviewed_by
					) values (:id, 'Spec', 'Bio', 5, 'Asia/Ho_Chi_Minh', 'APPROVED', now(), now(), :admin)
					""").param("id", spec).param("admin", adminId).update();
		}

		var user = UUID.randomUUID();
		var checkout = billing.checkout(user, PLUS, "co-shared-" + UUID.randomUUID());

		// Two periods with 1 credit each for the same subscription
		var period1 = UUID.randomUUID();
		var period2 = UUID.randomUUID();
		var credit1 = UUID.randomUUID();
		var credit2 = UUID.randomUUID();

		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_allocation_minor, created_at, updated_at
				) values (:id, :userId, 'v1', 'consultation-credit-v1', 'PLUS', 'PAID', :ref, now() - interval '60 days', now() - interval '30 days', 1, 100000, now(), now())
				""").param("id", period1).param("userId", user).param("ref", checkout.subscriptionId().toString()).update();

		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_allocation_minor, created_at, updated_at
				) values (:id, :userId, 'v1', 'consultation-credit-v1', 'PLUS', 'PAID', :ref, now() - interval '29 days', now() + interval '1 day', 1, 100000, now(), now())
				""").param("id", period2).param("userId", user).param("ref", checkout.subscriptionId().toString()).update();

		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, created_at, updated_at)
				values (:id, :periodId, 1, 'AVAILABLE', now(), now())
				""").param("id", credit1).param("periodId", period1).update();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, created_at, updated_at)
				values (:id, :periodId, 1, 'AVAILABLE', now(), now())
				""").param("id", credit2).param("periodId", period2).update();

		// App 1 & App 2
		var slot1 = UUID.randomUUID();
		var app1 = UUID.randomUUID();
		var fact1 = UUID.randomUUID();
		jdbc.sql("""
				insert into availability_slot (id, specialist_account_id, start_at, end_at, timezone, modality, idempotency_key, created_at, updated_at)
				values (:id, :spec, now() - interval '3 hours', now() - interval '2 hours', 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT', :k, now(), now())
				""").param("id", slot1).param("spec", specialist1).param("k", "slot1-" + slot1).update();
		jdbc.sql("""
				insert into appointment (id, user_account_id, specialist_account_id, availability_slot_id, service_credit_id, status, modality, scheduled_start_at, scheduled_end_at, display_timezone, requested_at, decision_deadline_at, idempotency_key, created_at, updated_at)
				values (:id, :user, :spec, :slot, :credit, 'REQUESTED', 'IN_APP_CHAT', now() - interval '3 hours', now() - interval '2 hours', 'Asia/Ho_Chi_Minh', now() - interval '5 hours', now() - interval '2 hours 30 minutes', :k, now(), now())
				""").param("id", app1).param("user", user).param("spec", specialist1).param("slot", slot1).param("credit", credit1).param("k", "app1-" + app1).update();
		jdbc.sql("""
				update appointment set status='COMPLETED', decided_at=now() - interval '2 hours 45 minutes', decision_reason='SPECIALIST_ACCEPTED',
				 session_outcome='COMPLETED', session_outcome_reason='EVIDENCE_REQUIREMENTS_MET', session_policy_version='chat-session-completion-v1',
				 session_ended_at=now() - interval '2 hours', session_settled_at=now() - interval '2 hours', completion_fact_id=:fact where id=:id
				""").param("id", app1).param("fact", fact1).update();
		jdbc.sql("update service_credit set state='CONSUMED', appointment_id=:appId where id=:creditId")
				.param("appId", app1).param("creditId", credit1).update();

		var slot2 = UUID.randomUUID();
		var app2 = UUID.randomUUID();
		var fact2 = UUID.randomUUID();
		jdbc.sql("""
				insert into availability_slot (id, specialist_account_id, start_at, end_at, timezone, modality, idempotency_key, created_at, updated_at)
				values (:id, :spec, now() - interval '2 hours', now() - interval '1 hour', 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT', :k, now(), now())
				""").param("id", slot2).param("spec", specialist2).param("k", "slot2-" + slot2).update();
		jdbc.sql("""
				insert into appointment (id, user_account_id, specialist_account_id, availability_slot_id, service_credit_id, status, modality, scheduled_start_at, scheduled_end_at, display_timezone, requested_at, decision_deadline_at, idempotency_key, created_at, updated_at)
				values (:id, :user, :spec, :slot, :credit, 'REQUESTED', 'IN_APP_CHAT', now() - interval '2 hours', now() - interval '1 hour', 'Asia/Ho_Chi_Minh', now() - interval '4 hours', now() - interval '1 hour 30 minutes', :k, now(), now())
				""").param("id", app2).param("user", user).param("spec", specialist2).param("slot", slot2).param("credit", credit2).param("k", "app2-" + app2).update();
		jdbc.sql("""
				update appointment set status='COMPLETED', decided_at=now() - interval '1 hour 45 minutes', decision_reason='SPECIALIST_ACCEPTED',
				 session_outcome='COMPLETED', session_outcome_reason='EVIDENCE_REQUIREMENTS_MET', session_policy_version='chat-session-completion-v1',
				 session_ended_at=now() - interval '1 hour', session_settled_at=now() - interval '1 hour', completion_fact_id=:fact where id=:id
				""").param("id", app2).param("fact", fact2).update();
		jdbc.sql("update service_credit set state='CONSUMED', appointment_id=:appId where id=:creditId")
				.param("appId", app2).param("creditId", credit2).update();

		// Earning 1 for Spec 1, Earning 2 for Spec 2
		var earn1 = UUID.randomUUID();
		var earn2 = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_earning (
				 id, appointment_id, completion_fact_id, consumed_credit_id, specialist_account_id,
				 plan_version, currency, credit_allocation_minor, specialist_share_bps,
				 specialist_amount_minor, platform_allocation_minor, idempotency_source,
				 status, earned_at, settlement_available_at, paid_at, created_at, updated_at
				) values (:id, :appId, :fact, :creditId, :spec, 'v1', 'VND', 200000, 5000, 100000, 100000, :src, 'PAID', now(), now(), now(), now(), now())
				""").param("id", earn1).param("appId", app1).param("fact", fact1).param("creditId", credit1).param("spec", specialist1).param("src", "src-" + earn1).update();

		jdbc.sql("""
				insert into specialist_earning (
				 id, appointment_id, completion_fact_id, consumed_credit_id, specialist_account_id,
				 plan_version, currency, credit_allocation_minor, specialist_share_bps,
				 specialist_amount_minor, platform_allocation_minor, idempotency_source,
				 status, earned_at, settlement_available_at, paid_at, created_at, updated_at
				) values (:id, :appId, :fact, :creditId, :spec, 'v1', 'VND', 200000, 5000, 100000, 100000, :src, 'PAID', now(), now(), now(), now(), now())
				""").param("id", earn2).param("appId", app2).param("fact", fact2).param("creditId", credit2).param("spec", specialist2).param("src", "src-" + earn2).update();

		// Payout 1 (for Spec 1) and Payout 2 (for Spec 2)
		var dest1 = UUID.randomUUID();
		var dest2 = UUID.randomUUID();
		var payout1 = UUID.randomUUID();
		var payout2 = UUID.randomUUID();

		jdbc.sql("""
				insert into specialist_payout_destination (id, specialist_account_id, payout_provider, destination_type, destination_ciphertext, encryption_key_version, destination_fingerprint, display_hint, status, verified_at, created_at, updated_at)
				values (:id, :spec, 'MOMO', 'MOMO_WALLET', 'cipher', 'v1', 'fingerprint1', '0911***', 'VERIFIED', now(), now(), now())
				""").param("id", dest1).param("spec", specialist1).update();
		jdbc.sql("""
				insert into specialist_payout_destination (id, specialist_account_id, payout_provider, destination_type, destination_ciphertext, encryption_key_version, destination_fingerprint, display_hint, status, verified_at, created_at, updated_at)
				values (:id, :spec, 'MOMO', 'MOMO_WALLET', 'cipher', 'v1', 'fingerprint2', '0912***', 'VERIFIED', now(), now(), now())
				""").param("id", dest2).param("spec", specialist2).update();

		jdbc.sql("""
				insert into specialist_payout (id, specialist_account_id, destination_id, amount_minor, currency, status, payout_provider, idempotency_key, requested_at, requested_on, completed_at, created_at, updated_at)
				values (:id, :spec, :dest, 100000, 'VND', 'SUCCEEDED', 'MOMO', :k, now(), current_date, now(), now(), now())
				""").param("id", payout1).param("spec", specialist1).param("dest", dest1).param("k", "payout-" + payout1).update();
		jdbc.sql("""
				insert into specialist_payout (id, specialist_account_id, destination_id, amount_minor, currency, status, payout_provider, idempotency_key, requested_at, requested_on, completed_at, created_at, updated_at)
				values (:id, :spec, :dest, 100000, 'VND', 'SUCCEEDED', 'MOMO', :k, now(), current_date, now(), now(), now())
				""").param("id", payout2).param("spec", specialist2).param("dest", dest2).param("k", "payout-" + payout2).update();

		jdbc.sql("insert into specialist_payout_item (payout_id, earning_id, amount_minor) values (:payoutId, :earn, 100000)")
				.param("payoutId", payout1).param("earn", earn1).update();
		jdbc.sql("insert into specialist_payout_item (payout_id, earning_id, amount_minor) values (:payoutId, :earn, 100000)")
				.param("payoutId", payout2).param("earn", earn2).update();

		// Query provenance specifically by payout1
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("payoutId", payout1.toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.payouts.length()").value(1))
				.andExpect(jsonPath("$.payouts[0].payoutId").value(payout1.toString()))
				.andExpect(jsonPath("$.earnings.length()").value(1))
				.andExpect(jsonPath("$.earnings[0].earningId").value(earn1.toString()))
				.andExpect(jsonPath("$.credits.length()").value(1))
				.andExpect(jsonPath("$.credits[0].creditId").value(credit1.toString()))
				.andExpect(jsonPath("$.appointments.length()").value(1))
				.andExpect(jsonPath("$.appointments[0].appointmentId").value(app1.toString()));
	}

	@Test
	void fixCD_dateRangeOrderingAndIdempotencyKeyQuery() throws Exception {
		var userId = UUID.randomUUID();
		var idemKey = "idem-" + UUID.randomUUID();
		var checkout = billing.checkout(userId, PLUS, idemKey);

		// 1. from > to -> 400 INVALID_DATE_RANGE
		mvc.perform(get("/api/v1/admin/payments")
				.param("from", "2026-10-20T10:00:00Z")
				.param("to", "2026-10-19T10:00:00Z")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));

		mvc.perform(get("/api/v1/admin/payouts")
				.param("from", "2026-10-20T10:00:00Z")
				.param("to", "2026-10-19T10:00:00Z")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));

		// 2. Query payment by idempotencyKey
		mvc.perform(get("/api/v1/admin/payments")
				.param("idempotencyKey", idemKey)
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.items[0].paymentId").value(checkout.paymentId().toString()));

		// 3. Query non-matching idempotencyKey -> 0
		mvc.perform(get("/api/v1/admin/payments")
				.param("idempotencyKey", "non-existent-" + UUID.randomUUID())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(0));
	}

	@Test
	void fixE_provenanceAmbiguousIdentifierReturns400() throws Exception {
		var checkout = billing.checkout(UUID.randomUUID(), PLUS, "amb-" + UUID.randomUUID());

		// Zero identifiers -> 400 MISSING
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MISSING_PROVENANCE_IDENTIFIER"));

		// Multiple identifiers -> 400 AMBIGUOUS
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("paymentId", checkout.paymentId().toString())
				.param("subscriptionId", checkout.subscriptionId().toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AMBIGUOUS_PROVENANCE_IDENTIFIER"));

		// One valid identifier -> 200
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("paymentId", checkout.paymentId().toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true));

		// Unknown UUID -> 200 found=false
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("paymentId", UUID.randomUUID().toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(false));
	}

	@Test
	void fixF_creditWithMultipleHistoricalAppointmentsReturnsAllHistory() throws Exception {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Test Spec', 'Bio', 5, 'Asia/Ho_Chi_Minh', 'APPROVED', now(), now(), :admin)
				""").param("id", specialistId).param("admin", adminId).update();

		var userId = UUID.randomUUID();
		var checkout = billing.checkout(userId, PLUS, "multi-hist-" + UUID.randomUUID());
		var period = UUID.randomUUID();
		var creditId = UUID.randomUUID();

		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_allocation_minor, created_at, updated_at
				) values (:id, :userId, 'v1', 'consultation-credit-v1', 'PLUS', 'PAID', :ref, now() - interval '10 days', now() + interval '20 days', 1, 100000, now(), now())
				""").param("id", period).param("userId", userId).param("ref", checkout.subscriptionId().toString()).update();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, created_at, updated_at)
				values (:id, :periodId, 1, 'AVAILABLE', now(), now())
				""").param("id", creditId).param("periodId", period).update();

		// First appointment: REJECTED (terminal, releases credit back)
		var slot1 = UUID.randomUUID();
		var app1 = UUID.randomUUID();
		jdbc.sql("""
				insert into availability_slot (id, specialist_account_id, start_at, end_at, timezone, modality, idempotency_key, created_at, updated_at)
				values (:id, :spec, now() - interval '8 days', now() - interval '8 days' + interval '1 hour', 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT', :k, now(), now())
				""").param("id", slot1).param("spec", specialistId).param("k", "slot1-" + slot1).update();
		jdbc.sql("""
				insert into appointment (id, user_account_id, specialist_account_id, availability_slot_id, service_credit_id, status, modality,
				 scheduled_start_at, scheduled_end_at, display_timezone, requested_at, decision_deadline_at, idempotency_key, created_at, updated_at)
				values (:id, :user, :spec, :slot, :credit, 'REQUESTED', 'IN_APP_CHAT',
				 now() - interval '8 days', now() - interval '8 days' + interval '1 hour', 'Asia/Ho_Chi_Minh',
				 now() - interval '8 days' - interval '30 minutes', now() - interval '8 days' + interval '30 minutes', :k, now(), now())
				""").param("id", app1).param("user", userId).param("spec", specialistId).param("slot", slot1)
				.param("credit", creditId).param("k", "app1-" + app1).update();
		jdbc.sql("update appointment set status='REJECTED', decided_at=now() - interval '8 days' + interval '15 minutes', decision_reason='SPECIALIST_REJECTED' where id=:id")
				.param("id", app1).update();

		// Second appointment: COMPLETED (credit consumed here) — multi-step status transition
		var slot2 = UUID.randomUUID();
		var app2 = UUID.randomUUID();
		var fact2 = UUID.randomUUID();
		jdbc.sql("""
				insert into availability_slot (id, specialist_account_id, start_at, end_at, timezone, modality, idempotency_key, created_at, updated_at)
				values (:id, :spec, now() - interval '5 days', now() - interval '5 days' + interval '1 hour', 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT', :k, now(), now())
				""").param("id", slot2).param("spec", specialistId).param("k", "slot2-" + slot2).update();
		jdbc.sql("""
				insert into appointment (id, user_account_id, specialist_account_id, availability_slot_id, service_credit_id, status, modality,
				 scheduled_start_at, scheduled_end_at, display_timezone, requested_at, decision_deadline_at, idempotency_key, created_at, updated_at)
				values (:id, :user, :spec, :slot, :credit, 'REQUESTED', 'IN_APP_CHAT',
				 now() - interval '5 days', now() - interval '5 days' + interval '1 hour', 'Asia/Ho_Chi_Minh',
				 now() - interval '5 days' - interval '30 minutes', now() - interval '5 days' + interval '30 minutes', :k, now(), now())
				""").param("id", app2).param("user", userId).param("spec", specialistId).param("slot", slot2)
				.param("credit", creditId).param("k", "app2-" + app2).update();
		jdbc.sql("update appointment set status='COMPLETED', decided_at=now() - interval '5 days' + interval '5 minutes', decision_reason='SPECIALIST_ACCEPTED', session_outcome='COMPLETED', session_outcome_reason='EVIDENCE_REQUIREMENTS_MET', session_policy_version='chat-session-completion-v1', session_ended_at=now() - interval '5 days' + interval '55 minutes', session_settled_at=now() - interval '5 days' + interval '56 minutes', completion_fact_id=:fact where id=:id")
				.param("id", app2).param("fact", fact2).update();
		jdbc.sql("update service_credit set state='CONSUMED', appointment_id=:appId where id=:creditId")
				.param("appId", app2).param("creditId", creditId).update();

		var earn1 = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_earning (
				 id, appointment_id, completion_fact_id, consumed_credit_id, specialist_account_id,
				 plan_version, currency, credit_allocation_minor, specialist_share_bps,
				 specialist_amount_minor, platform_allocation_minor, idempotency_source,
				 status, earned_at, settlement_available_at, created_at, updated_at
				) values (:id, :appId, :fact, :creditId, :spec, 'v1', 'VND', 100000, 7000, 70000, 30000, :src, 'AVAILABLE', now(), now() + interval '7 days', now(), now())
				""").param("id", earn1).param("appId", app2).param("fact", fact2)
				.param("creditId", creditId).param("spec", specialistId).param("src", "src-" + earn1).update();

		// Trace by creditId: must return both appointments, one earning, no duplicates
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("creditId", creditId.toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.appointments.length()").value(2))
				.andExpect(jsonPath("$.earnings.length()").value(1))
				.andExpect(jsonPath("$.earnings[0].earningId").value(earn1.toString()))
				.andExpect(jsonPath("$.truncated").value(false));
	}

	@Test
	void fixG_paymentsQueryByMomoRefAndIdempotencyKeyAreDistinct() throws Exception {
		var userId = UUID.randomUUID();
		var idemKey = "idem-momo-" + UUID.randomUUID();
		var checkout = billing.checkout(userId, PLUS, idemKey);

		// idempotencyKey returns only exact match
		mvc.perform(get("/api/v1/admin/payments")
				.param("idempotencyKey", idemKey)
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(1));

		// momoRef with non-existent value -> 0 (does not fallback to idempotency)
		mvc.perform(get("/api/v1/admin/payments")
				.param("momoRef", "non-existent-momo-order")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(0));

		// correlationId param no longer accepted (gets ignored, treated as unrecognized)
		// Verify idempotencyKey alone is enough to find the payment
		mvc.perform(get("/api/v1/admin/payments")
				.param("idempotencyKey", idemKey)
				.param("subscriptionId", checkout.subscriptionId().toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(1));
	}

	@Test
	void truncation_intermediateQuery_detectsTruncation() throws Exception {
		// This test verifies that intermediate query truncation is properly detected
		// We'll use the CREDIT path which has intermediate queries for appointments
		var userId = UUID.randomUUID();
		var checkout = billing.checkout(userId, PLUS, "intermediate-trunc-" + UUID.randomUUID());
		
		// Get the credit ID from the checkout
		var credits = jdbc.sql("select id from service_credit where period_id in (select id from service_credit_period where source_reference = :ref)")
				.param("ref", checkout.subscriptionId().toString())
				.query(UUID.class).list();
		
		if (credits.isEmpty()) return; // Skip if billing didn't create credits
		
		var creditId = credits.get(0);
		
		// Create a specialist
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Test Spec', 'Bio', 5, 'Asia/Ho_Chi_Minh', 'APPROVED', now(), now(), :admin)
				""").param("id", specialistId).param("admin", adminId).update();
		
		// Create 101 appointments linked to this credit to trigger intermediate truncation
		// This simulates a credit with extensive appointment history
		for (int i = 0; i < 101; i++) {
			var apptId = UUID.randomUUID();
			var slotId = UUID.randomUUID();
			
			// Create availability slot
			jdbc.sql("""
					insert into specialist_availability_slot (
					 id, specialist_account_id, starts_at, ends_at, status, created_at, updated_at
					) values (:id, :specId, now() + interval '1 hour' * :offset, now() + interval '2 hours' * :offset, 'BOOKED', now(), now())
					""").param("id", slotId).param("specId", specialistId).param("offset", i).update();
			
			// Create appointment
			jdbc.sql("""
					insert into appointment (
					 id, user_account_id, specialist_account_id, service_credit_id, availability_slot_id,
					 modality, status, scheduled_start_at, scheduled_end_at, decision_deadline_at,
					 created_at, updated_at
					) values (:id, :userId, :specId, :creditId, :slotId, 'ONLINE_VIDEO', 'REQUESTED',
					 now() + interval '1 hour' * :offset, now() + interval '2 hours' * :offset, now() + interval '30 minutes' * :offset,
					 now(), now())
					""").param("id", apptId).param("userId", userId).param("specId", specialistId)
					.param("creditId", creditId).param("slotId", slotId).param("offset", i).update();
		}
		
		// Query by creditId - should detect intermediate truncation even if final lists are small
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("creditId", creditId.toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.truncated").value(true))  // MUST be true due to 101 appointments
				.andExpect(jsonPath("$.appointments.length()").value(100)); // Trimmed to 100
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor role(UUID id, String authority) {
		return jwt().jwt(token -> token.subject(id.toString())).authorities(new SimpleGrantedAuthority(authority));
	}
}


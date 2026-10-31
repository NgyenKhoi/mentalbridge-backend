package com.mentalbridge.consultation.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
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
	void adminPaymentDateWindowIsHalfOpenAndLegacyToRemainsInclusive() throws Exception {
		var checkout = billing.checkout(UUID.randomUUID(), PLUS, "date-window-" + UUID.randomUUID());
		var boundary = Instant.parse("2026-10-23T17:00:00Z");
		jdbc.sql("update payment_transaction set created_at=:at, expires_at=:expires where id=:id")
				.param("at", Timestamp.from(boundary.minusNanos(1000)))
				.param("expires", Timestamp.from(boundary.plusSeconds(900)))
				.param("id", checkout.paymentId()).update();
		var request = get("/api/v1/admin/payments")
				.param("paymentId", checkout.paymentId().toString())
				.param("from", "2026-10-22T17:00:00Z")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN"));
		mvc.perform(request.param("toExclusive", boundary.toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
		jdbc.sql("update payment_transaction set created_at=:at, expires_at=:expires where id=:id")
				.param("at", Timestamp.from(boundary))
				.param("expires", Timestamp.from(boundary.plusSeconds(900)))
				.param("id", checkout.paymentId()).update();
		mvc.perform(get("/api/v1/admin/payments").param("paymentId", checkout.paymentId().toString())
					.param("toExclusive", boundary.toString()).with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
		mvc.perform(get("/api/v1/admin/payments").param("paymentId", checkout.paymentId().toString())
					.param("to", boundary.toString()).with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
		mvc.perform(get("/api/v1/admin/payments").param("to", boundary.toString())
					.param("toExclusive", boundary.toString()).with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/admin/payments").param("from", boundary.toString())
					.param("toExclusive", boundary.toString()).with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isBadRequest());
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
	void mixedProviderReceiptsKeepTheirProviderAndContributeToTheSameBoundedCount() throws Exception {
		var before = mvc.perform(get("/api/v1/admin/commercial/discrepancies")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN"))).andExpect(status().isOk()).andReturn();
		var baseline = json.readTree(before.getResponse().getContentAsByteArray()).get("totalDiscrepancies").asInt();
		var momoId = UUID.randomUUID();
		jdbc.sql("""
				insert into momo_payment_ipn (id,contract_version,deduplication_key,partner_code,order_id,request_id,
				 amount_minor,currency,order_info_sha256,order_type,trans_id,result_code,result_message,pay_type,
				 response_time_epoch_ms,extra_data_sha256,provider_occurred_at,payload_sha256,signature_key_version,
				 signature_verified_at,processing_status,failure_code,received_at)
				values (:id,'momo-v2',:dedupe,'test-partner',:orderId,:requestId,5000,'VND',:hash,'momo_wallet',
				 :transId,0,'Success','qr',1,:hash,now(),:hash,'test-v1',now(),'VERIFIED_UNMATCHED','ORDER_NOT_FOUND',now())
				""").param("id", momoId).param("dedupe", UUID.randomUUID().toString().replace("-", "").repeat(2))
				.param("orderId", "order-" + momoId).param("requestId", "request-" + momoId)
				.param("transId", UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE)
				.param("hash", "a".repeat(64)).update();
		var unmatched = insertPayosReceipt("WEBHOOK", "VERIFIED_UNMATCHED", "AMOUNT_MISMATCH");
		var reused = insertPayosReceipt("RECONCILIATION", "VERIFIED_UNMATCHED", "REFERENCE_CONFLICT");
		var processed = insertPayosReceipt("WEBHOOK", "PROCESSED", null);
		var result = mvc.perform(get("/api/v1/admin/commercial/discrepancies")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN"))).andExpect(status().isOk()).andReturn();
		var body = json.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.get("totalDiscrepancies").asInt()).isEqualTo(baseline + 3);
		var receipts = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
		body.get("webhooks").forEach(receipt -> receipts.put(receipt.get("id").asText(), receipt));
		assertThat(receipts.get(momoId.toString()).get("provider").asText()).isEqualTo("MOMO");
		assertThat(receipts.get(unmatched.toString()).get("provider").asText()).isEqualTo("PAYOS");
		assertThat(receipts.get(unmatched.toString()).get("orderId").asText()).isEqualTo("123");
		assertThat(receipts.get(unmatched.toString()).get("transId").isNull()).isTrue();
		assertThat(receipts.get(unmatched.toString()).get("discrepancyType").asText()).isEqualTo("VERIFIED_UNMATCHED");
		assertThat(receipts.get(reused.toString()).get("discrepancyType").asText()).isEqualTo("DUPLICATE_TRANS_ID");
		assertThat(receipts).doesNotContainKey(processed.toString());
		assertThat(result.getResponse().getContentAsString()).doesNotContain("private-reference-", "payload_sha256", "payment_link_id");
	}

	private UUID insertPayosReceipt(String source, String status, String failureCode) {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into payos_payment_webhook (id,contract_version,source,deduplication_key,order_code,amount_minor,
				 payment_link_id,reference,payload_sha256,signature_key_version,signature_verified_at,
				 processing_status,failure_code,received_at)
				values (:id,'payos-v2',:source,:dedupe,123,5000,'sample-link',:reference,:hash,'test-v1',now(),
				 :status,:failure,now())
				""").param("id", id).param("source", source)
				.param("dedupe", UUID.randomUUID().toString().replace("-", "").repeat(2))
				.param("reference", "private-reference-" + id).param("hash", "b".repeat(64))
				.param("status", status).param("failure", failureCode).update();
		return id;
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
		var oldReference = "old-ref-" + payoutId;
		var latestReference = "latest-ref-" + payoutId;
		for (int attempt = 1; attempt <= 2; attempt++) {
			jdbc.sql("""
					insert into specialist_payout_attempt (id, payout_id, payout_provider, attempt_number,
					 provider_idempotency_key, provider_payout_reference, status, requested_at, created_at, updated_at)
					values (:id, :payoutId, 'MOMO', :attempt, :key, :reference, :status, now(), now(), now())
					""").param("id", UUID.randomUUID()).param("payoutId", payoutId).param("attempt", attempt)
					.param("key", "attempt-" + attempt + "-" + payoutId)
					.param("reference", attempt == 1 ? oldReference : latestReference)
					.param("status", attempt == 1 ? "FAILED" : "SUCCEEDED").update();
		}
		mvc.perform(get("/api/v1/admin/payouts").param("providerReference", oldReference)
					.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.items[0].payoutId").value(payoutId.toString()))
				.andExpect(jsonPath("$.items[0].providerReference").value(latestReference));

		var payoutBoundary = Instant.parse("2026-10-23T17:00:00Z");
		jdbc.sql("update specialist_payout set requested_at=:at where id=:id")
				.param("at", Timestamp.from(payoutBoundary.minusNanos(1000))).param("id", payoutId).update();
		mvc.perform(get("/api/v1/admin/payouts").param("payoutId", payoutId.toString())
					.param("toExclusive", payoutBoundary.toString()).with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.items[0].earningCount").value(2));
		jdbc.sql("update specialist_payout set requested_at=:at where id=:id")
				.param("at", Timestamp.from(payoutBoundary)).param("id", payoutId).update();
		mvc.perform(get("/api/v1/admin/payouts").param("payoutId", payoutId.toString())
					.param("toExclusive", payoutBoundary.toString()).with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
		mvc.perform(get("/api/v1/admin/payouts").param("payoutId", payoutId.toString())
					.param("to", payoutBoundary.toString()).with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
		mvc.perform(get("/api/v1/admin/payouts").param("to", payoutBoundary.toString())
					.param("toExclusive", payoutBoundary.toString()).with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isBadRequest());

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
	void adminListsValidateDatesAndExposeOnlyPageLocalCounts() throws Exception {
		var userId = UUID.randomUUID();
		var checkout = billing.checkout(userId, PLUS, "filter-test-" + UUID.randomUUID());

		// Payment count covers only the requested page, including an empty page beyond the result.
		mvc.perform(get("/api/v1/admin/payments")
				.param("paymentId", checkout.paymentId().toString())
				.param("offset", "0")
				.param("limit", "1")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(1));
		mvc.perform(get("/api/v1/admin/payments")
				.param("paymentId", checkout.paymentId().toString())
				.param("offset", "1")
				.param("limit", "1")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(0));

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
	void creditProvenanceWith99AppointmentsReturnsAllWithoutTruncation() throws Exception {
		assertCreditAppointmentBoundary(99, 99, false);
	}

	@Test
	void creditProvenanceWith100AppointmentsReturnsAllWithoutTruncation() throws Exception {
		assertCreditAppointmentBoundary(100, 100, false);
	}

	@Test
	void creditProvenanceWith101AppointmentsReturnsBoundedResultWithTruncation() throws Exception {
		assertCreditAppointmentBoundary(101, 100, true);
	}

	@Test
	void subscriptionProvenanceWith101CreditsPropagatesIntermediateTruncation() throws Exception {
		var userId = UUID.randomUUID();
		var checkout = billing.checkout(userId, PLUS, "subscription-credit-trunc-" + UUID.randomUUID());
		var creditIds = new HashSet<UUID>();
		var base = Instant.parse("2025-01-01T00:00:00Z");

		for (int i = 0; i < 101; i++) {
			var periodId = UUID.randomUUID();
			var creditId = UUID.randomUUID();
			var periodStart = base.plusSeconds(i * 86400L);
			jdbc.sql("""
					insert into service_credit_period (
					 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
					 period_start, period_end, allocated_count, credit_allocation_minor, created_at, updated_at
					) values (:id, :userId, 'v1', 'consultation-credit-v1', 'PLUS', 'PAID', :ref,
					 :periodStart, :periodEnd, 1, 100000, :createdAt, :createdAt)
					""").param("id", periodId).param("userId", userId)
					.param("ref", checkout.subscriptionId().toString())
					.param("periodStart", Timestamp.from(periodStart))
					.param("periodEnd", Timestamp.from(periodStart.plusSeconds(86400)))
					.param("createdAt", Timestamp.from(periodStart)).update();
			jdbc.sql("""
					insert into service_credit (id, period_id, ordinal, state, created_at, updated_at)
					values (:id, :periodId, 1, 'AVAILABLE', :createdAt, :createdAt)
					""").param("id", creditId).param("periodId", periodId)
					.param("createdAt", Timestamp.from(periodStart)).update();
			creditIds.add(creditId);
		}

		var fixtureCount = jdbc.sql("""
				select count(*) from service_credit c
				join service_credit_period p on p.id=c.period_id
				where p.source='PAID' and p.source_reference=:subscriptionId
				""").param("subscriptionId", checkout.subscriptionId().toString()).query(Integer.class).single();
		assertThat(fixtureCount).isEqualTo(101);

		var result = mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("subscriptionId", checkout.subscriptionId().toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.credits.length()").value(100))
				.andExpect(jsonPath("$.truncated").value(true))
				.andReturn();

		var body = json.readTree(result.getResponse().getContentAsByteArray());
		var returnedCreditIds = new HashSet<String>();
		body.path("credits").forEach(node -> returnedCreditIds.add(node.path("creditId").asText()));
		assertThat(returnedCreditIds).hasSize(100);
		assertThat(returnedCreditIds).allMatch(id -> creditIds.contains(UUID.fromString(id)));
	}

	private void assertCreditAppointmentBoundary(int fixtureSize, int expectedReturned, boolean expectedTruncated)
			throws Exception {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Test Spec', 'Bio', 5, 'Asia/Ho_Chi_Minh', 'APPROVED', now(), now(), :admin)
				""").param("id", specialistId).param("admin", adminId).update();

		var userId = UUID.randomUUID();
		var checkout = billing.checkout(userId, PLUS, "credit-history-" + UUID.randomUUID());
		var periodId = UUID.randomUUID();
		var creditId = UUID.randomUUID();
		var base = Instant.parse("2025-01-01T00:00:00Z");
		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_allocation_minor, created_at, updated_at
				) values (:id, :userId, 'v1', 'consultation-credit-v1', 'PLUS', 'PAID', :ref,
				 :periodStart, :periodEnd, 1, 100000, :createdAt, :createdAt)
				""").param("id", periodId).param("userId", userId)
				.param("ref", checkout.subscriptionId().toString())
				.param("periodStart", Timestamp.from(base.minusSeconds(86400)))
				.param("periodEnd", Timestamp.from(base.plusSeconds(365 * 86400L)))
				.param("createdAt", Timestamp.from(base.minusSeconds(86400))).update();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, created_at, updated_at)
				values (:id, :periodId, 1, 'AVAILABLE', :createdAt, :createdAt)
				""").param("id", creditId).param("periodId", periodId)
				.param("createdAt", Timestamp.from(base)).update();

		Set<UUID> appointmentIds = new HashSet<>();
		for (int i = 0; i < fixtureSize; i++) {
			var apptId = UUID.randomUUID();
			var slotId = UUID.randomUUID();
			var start = base.plusSeconds(i * 7200L);

			jdbc.sql("""
					insert into availability_slot (
					 id, specialist_account_id, start_at, end_at, timezone, modality,
					 idempotency_key, created_at, updated_at
					) values (:id, :specId, :startAt, :endAt, 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT',
					 :key, :createdAt, :createdAt)
					""").param("id", slotId).param("specId", specialistId)
					.param("startAt", Timestamp.from(start)).param("endAt", Timestamp.from(start.plusSeconds(3600)))
					.param("key", "slot-history-" + slotId).param("createdAt", Timestamp.from(base)).update();

			jdbc.sql("""
					insert into appointment (
					 id, user_account_id, specialist_account_id, availability_slot_id, service_credit_id,
					 status, modality, scheduled_start_at, scheduled_end_at, display_timezone,
					 requested_at, decision_deadline_at, idempotency_key, decided_at, decision_reason,
					 created_at, updated_at
					) values (:id, :userId, :specId, :slotId, :creditId,
					 'REJECTED', 'IN_APP_CHAT', :startAt, :endAt, 'Asia/Ho_Chi_Minh',
					 :requestedAt, :deadlineAt, :key, :decidedAt, 'SPECIALIST_REJECTED',
					 :createdAt, :createdAt)
					""").param("id", apptId).param("userId", userId).param("specId", specialistId)
					.param("creditId", creditId).param("slotId", slotId)
					.param("startAt", Timestamp.from(start)).param("endAt", Timestamp.from(start.plusSeconds(3600)))
					.param("requestedAt", Timestamp.from(start.minusSeconds(1800)))
					.param("deadlineAt", Timestamp.from(start.minusSeconds(900)))
					.param("decidedAt", Timestamp.from(start.minusSeconds(600)))
					.param("key", "appointment-history-" + apptId)
					.param("createdAt", Timestamp.from(start.minusSeconds(1800))).update();
			appointmentIds.add(apptId);
		}

		var fixtureCount = jdbc.sql("select count(*) from appointment where service_credit_id=:creditId")
				.param("creditId", creditId).query(Integer.class).single();
		assertThat(fixtureCount).isEqualTo(fixtureSize);

		var result = mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("creditId", creditId.toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.appointments.length()").value(expectedReturned))
				.andExpect(jsonPath("$.truncated").value(expectedTruncated))
				.andReturn();

		var body = json.readTree(result.getResponse().getContentAsByteArray());
		var returnedAppointmentIds = new HashSet<String>();
		body.path("appointments").forEach(node -> {
			assertThat(node.path("serviceCreditId").asText()).isEqualTo(creditId.toString());
			returnedAppointmentIds.add(node.path("appointmentId").asText());
		});
		assertThat(returnedAppointmentIds).hasSize(expectedReturned);
		assertThat(returnedAppointmentIds).allMatch(id -> appointmentIds.contains(UUID.fromString(id)));
	}

	@Test
	void paymentProvenanceDistinguishesDirectPaymentFromRelatedSubscriptionHistoryAndPendingUpgrade() throws Exception {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Spec A', 'Bio', 5, 'Asia/Ho_Chi_Minh', 'APPROVED', now(), now(), :admin)
				""").param("id", specialistId).param("admin", adminId).update();

		var userId = UUID.randomUUID();
		var purchaseCheckout = billing.checkout(userId, PLUS, "purchase-idem-" + UUID.randomUUID());
		var p1 = purchaseCheckout.paymentId();
		var subId = purchaseCheckout.subscriptionId();
		var purchaseAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		var purchaseEnd = java.time.OffsetDateTime.ofInstant(purchaseAt, java.time.ZoneOffset.UTC)
				.plusMonths(1).toInstant();

		// Succeeded purchase
		jdbc.sql("update payment_transaction set status='SUCCEEDED', paid_at=:paidAt where id=:id")
				.param("paidAt", Timestamp.from(purchaseAt)).param("id", p1).update();
		jdbc.sql("update user_subscription set status='ACTIVE', current_period_start=:start, current_period_end=:end where id=:id")
				.param("start", Timestamp.from(purchaseAt)).param("end", Timestamp.from(purchaseEnd))
				.param("id", subId).update();

		// Credit C1 for purchase
		var periodId = UUID.randomUUID();
		var c1 = UUID.randomUUID();
		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_allocation_minor, created_at, updated_at
				) values (:id, :userId, :planVersion, 'consultation-credit-v1', 'PLUS', 'PAID', :ref, :start, :end, 1, 200000, now(), now())
				""").param("id", periodId).param("userId", userId).param("planVersion", PLUS.toString())
				.param("ref", subId.toString()).param("start", Timestamp.from(purchaseAt))
				.param("end", Timestamp.from(purchaseEnd)).update();
		jdbc.sql("insert into service_credit (id, period_id, ordinal, state, created_at, updated_at) values (:id, :periodId, 1, 'AVAILABLE', now(), now())")
				.param("id", c1).param("periodId", periodId).update();

		// Appointment A1 consuming C1 -> E1 -> Payout1
		var slot1 = UUID.randomUUID();
		var a1 = UUID.randomUUID();
		var fact1 = UUID.randomUUID();
		jdbc.sql("""
				insert into availability_slot (id, specialist_account_id, start_at, end_at, timezone, modality, idempotency_key, created_at, updated_at)
				values (:id, :spec, now() - interval '3 hours', now() - interval '2 hours', 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT', :k, now(), now())
				""").param("id", slot1).param("spec", specialistId).param("k", "slot-" + slot1).update();
		jdbc.sql("""
				insert into appointment (id, user_account_id, specialist_account_id, availability_slot_id, service_credit_id, status, modality, scheduled_start_at, scheduled_end_at, display_timezone, requested_at, decision_deadline_at, idempotency_key, created_at, updated_at)
				values (:id, :user, :spec, :slot, :credit, 'REQUESTED', 'IN_APP_CHAT', now() - interval '3 hours', now() - interval '2 hours', 'Asia/Ho_Chi_Minh', now() - interval '5 hours', now() - interval '2 hours 30 minutes', :k, now(), now())
				""").param("id", a1).param("user", userId).param("spec", specialistId).param("slot", slot1).param("credit", c1).param("k", "app-" + a1).update();
		jdbc.sql("""
				update appointment set status='COMPLETED', decided_at=now() - interval '2 hours 45 minutes', decision_reason='SPECIALIST_ACCEPTED',
				 session_outcome='COMPLETED', session_outcome_reason='EVIDENCE_REQUIREMENTS_MET', session_policy_version='chat-session-completion-v1',
				 session_ended_at=now() - interval '2 hours', session_settled_at=now() - interval '2 hours', completion_fact_id=:fact where id=:id
				""").param("id", a1).param("fact", fact1).update();
		jdbc.sql("update service_credit set state='CONSUMED', appointment_id=:appId where id=:creditId")
				.param("appId", a1).param("creditId", c1).update();

		var earn1 = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_earning (
				 id, appointment_id, completion_fact_id, consumed_credit_id, specialist_account_id,
				 plan_version, currency, credit_allocation_minor, specialist_share_bps,
				 specialist_amount_minor, platform_allocation_minor, idempotency_source,
				 status, earned_at, settlement_available_at, paid_at, created_at, updated_at
				) values (:id, :appId, :fact, :creditId, :spec, 'v1', 'VND', 200000, 5000, 100000, 100000, :src, 'PAID', now(), now(), now(), now(), now())
				""").param("id", earn1).param("appId", a1).param("fact", fact1).param("creditId", c1).param("spec", specialistId).param("src", "src-" + earn1).update();

		var dest1 = UUID.randomUUID();
		var payout1 = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_payout_destination (id, specialist_account_id, payout_provider, destination_type, destination_ciphertext, encryption_key_version, destination_fingerprint, display_hint, status, verified_at, created_at, updated_at)
				values (:id, :spec, 'MOMO', 'MOMO_WALLET', 'cipher', 'v1', 'fp', '0911***', 'VERIFIED', now(), now(), now())
				""").param("id", dest1).param("spec", specialistId).update();
		jdbc.sql("""
				insert into specialist_payout (id, specialist_account_id, destination_id, amount_minor, currency, status, payout_provider, idempotency_key, requested_at, requested_on, completed_at, created_at, updated_at)
				values (:id, :spec, :dest, 100000, 'VND', 'SUCCEEDED', 'MOMO', :k, now(), current_date, now(), now(), now())
				""").param("id", payout1).param("spec", specialistId).param("dest", dest1).param("k", "payout-" + payout1).update();
		jdbc.sql("insert into specialist_payout_item (payout_id, earning_id, amount_minor) values (:payoutId, :earn, 100000)")
				.param("payoutId", payout1).param("earn", earn1).update();

		// Now user creates a PENDING upgrade payment P2
		var p2 = UUID.randomUUID();
		var upgradeId = UUID.randomUUID();
		var premiumPlanVersionId = UUID.fromString("62200000-0000-4000-8000-000000000003");
		jdbc.sql("""
				insert into payment_transaction (
				 id, subscription_id, target_plan_version_id, payment_provider, momo_order_id, momo_request_id,
				 amount_minor, currency, status, purpose, idempotency_key, expires_at, checkout_url, created_at, updated_at
				) values (
				 :id, :subId, :planId, 'MOMO', :orderId, :reqId,
				 150000, 'VND', 'PENDING', 'UPGRADE', :key, now() + interval '1 hour', :checkoutUrl, now(), now()
				)
				""").param("id", p2).param("subId", subId).param("planId", premiumPlanVersionId)
				.param("orderId", "order-" + p2).param("reqId", "req-" + p2).param("key", "idem-" + p2)
				.param("checkoutUrl", "https://test.momo.vn/checkout/" + p2).update();

		jdbc.sql("""
				insert into subscription_upgrade (
				 id, subscription_id, from_plan_version_id, to_plan_version_id, payment_id,
				 old_period_start, old_period_end, total_period_seconds, remaining_period_seconds,
				 remaining_feature_value_minor, available_credit_value_minor, offset_minor, amount_due_minor,
				 currency, status, idempotency_key, quoted_at, quote_expires_at, created_at, updated_at
				) values (
				 :id, :subId, :fromPlan, :toPlan, :paymentId,
				 now(), now() + interval '30 days', 2592000, 2592000,
				 50000, 0, 50000, 150000,
				 'VND', 'PENDING_PAYMENT', :key, now(), now() + interval '1 hour', now(), now()
				)
				""").param("id", upgradeId).param("subId", subId)
				.param("fromPlan", PLUS).param("toPlan", premiumPlanVersionId)
				.param("paymentId", p2).param("key", "upg-idem-" + p2).update();

		// 1. Query by pending upgrade payment P2: MUST NOT associate with previous purchase's credits, appointments, earnings, payouts!
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("paymentId", p2.toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.queryIdentifierType").value("PAYMENT"))
				.andExpect(jsonPath("$.queryIdentifierValue").value(p2.toString()))
				.andExpect(jsonPath("$.payments.length()").value(1))
				.andExpect(jsonPath("$.payments[0].paymentId").value(p2.toString()))
				.andExpect(jsonPath("$.credits").isEmpty())
				.andExpect(jsonPath("$.appointments").isEmpty())
				.andExpect(jsonPath("$.earnings").isEmpty())
				.andExpect(jsonPath("$.payouts").isEmpty())
				.andExpect(jsonPath("$.subscription.subscriptionId").value(subId.toString()));

		// 2. Query by succeeded purchase payment P1: correctly traces C1, A1, E1, Payout1!
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("paymentId", p1.toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.payments.length()").value(1))
				.andExpect(jsonPath("$.payments[0].paymentId").value(p1.toString()))
				.andExpect(jsonPath("$.credits.length()").value(1))
				.andExpect(jsonPath("$.credits[0].creditId").value(c1.toString()))
				.andExpect(jsonPath("$.appointments.length()").value(1))
				.andExpect(jsonPath("$.appointments[0].appointmentId").value(a1.toString()))
				.andExpect(jsonPath("$.earnings.length()").value(1))
				.andExpect(jsonPath("$.earnings[0].earningId").value(earn1.toString()))
				.andExpect(jsonPath("$.payouts.length()").value(1))
				.andExpect(jsonPath("$.payouts[0].payoutId").value(payout1.toString()));

		// 3. Query by subscriptionId: returns whole subscription history (both payments)
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("subscriptionId", subId.toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.payments.length()").value(2))
				.andExpect(jsonPath("$.credits.length()").value(1))
				.andExpect(jsonPath("$.appointments.length()").value(1));
	}

	@Test
	void creditProvenanceWith100DeclinedAnd1CompletedPreservesConsumingAppointmentAndEarnings() throws Exception {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Spec Preserved', 'Bio', 5, 'Asia/Ho_Chi_Minh', 'APPROVED', now(), now(), :admin)
				""").param("id", specialistId).param("admin", adminId).update();

		var userId = UUID.randomUUID();
		var checkout = billing.checkout(userId, PLUS, "cred-trunc-pres-" + UUID.randomUUID());
		var periodId = UUID.randomUUID();
		var creditId = UUID.randomUUID();
		var base = Instant.parse("2025-01-01T00:00:00Z");

		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_allocation_minor, created_at, updated_at
				) values (:id, :userId, 'v1', 'consultation-credit-v1', 'PLUS', 'PAID', :ref,
				 :periodStart, :periodEnd, 1, 200000, :createdAt, :createdAt)
				""").param("id", periodId).param("userId", userId)
				.param("ref", checkout.subscriptionId().toString())
				.param("periodStart", Timestamp.from(base.minusSeconds(86400)))
				.param("periodEnd", Timestamp.from(base.plusSeconds(365 * 86400L)))
				.param("createdAt", Timestamp.from(base.minusSeconds(86400))).update();

		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, created_at, updated_at)
				values (:id, :periodId, 1, 'AVAILABLE', :createdAt, :createdAt)
				""").param("id", creditId).param("periodId", periodId)
				.param("createdAt", Timestamp.from(base)).update();

		// Insert 100 REJECTED appointments
		for (int i = 0; i < 100; i++) {
			var apptId = UUID.randomUUID();
			var slotId = UUID.randomUUID();
			var start = base.plusSeconds(i * 7200L);

			jdbc.sql("""
					insert into availability_slot (
					 id, specialist_account_id, start_at, end_at, timezone, modality,
					 idempotency_key, created_at, updated_at
					) values (:id, :specId, :startAt, :endAt, 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT',
					 :key, :createdAt, :createdAt)
					""").param("id", slotId).param("specId", specialistId)
					.param("startAt", Timestamp.from(start)).param("endAt", Timestamp.from(start.plusSeconds(3600)))
					.param("key", "slot-declined-" + slotId).param("createdAt", Timestamp.from(base)).update();

			jdbc.sql("""
					insert into appointment (
					 id, user_account_id, specialist_account_id, availability_slot_id, service_credit_id,
					 status, modality, scheduled_start_at, scheduled_end_at, display_timezone,
					 requested_at, decision_deadline_at, idempotency_key, decided_at, decision_reason,
					 created_at, updated_at
					) values (:id, :userId, :specId, :slotId, :creditId,
					 'REJECTED', 'IN_APP_CHAT', :startAt, :endAt, 'Asia/Ho_Chi_Minh',
					 :requestedAt, :deadlineAt, :key, :decidedAt, 'SPECIALIST_REJECTED',
					 :createdAt, :createdAt)
					""").param("id", apptId).param("userId", userId).param("specId", specialistId)
					.param("creditId", creditId).param("slotId", slotId)
					.param("startAt", Timestamp.from(start)).param("endAt", Timestamp.from(start.plusSeconds(3600)))
					.param("requestedAt", Timestamp.from(start.minusSeconds(1800)))
					.param("deadlineAt", Timestamp.from(start.minusSeconds(900)))
					.param("decidedAt", Timestamp.from(start.minusSeconds(600)))
					.param("key", "app-declined-" + apptId)
					.param("createdAt", Timestamp.from(start.minusSeconds(1800))).update();
		}

		// Insert 101st appointment: COMPLETED, consumes the credit!
		var completedApptId = UUID.randomUUID();
		var completedSlotId = UUID.randomUUID();
		var completedFact = UUID.randomUUID();
		var completedStart = base.plusSeconds(100 * 7200L);

		jdbc.sql("""
				insert into availability_slot (
				 id, specialist_account_id, start_at, end_at, timezone, modality,
				 idempotency_key, created_at, updated_at
				) values (:id, :specId, :startAt, :endAt, 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT',
				 :key, :createdAt, :createdAt)
				""").param("id", completedSlotId).param("specId", specialistId)
				.param("startAt", Timestamp.from(completedStart)).param("endAt", Timestamp.from(completedStart.plusSeconds(3600)))
				.param("key", "slot-completed-" + completedSlotId).param("createdAt", Timestamp.from(base)).update();

		jdbc.sql("""
				insert into appointment (
				 id, user_account_id, specialist_account_id, availability_slot_id, service_credit_id,
				 status, modality, scheduled_start_at, scheduled_end_at, display_timezone,
				 requested_at, decision_deadline_at, idempotency_key, created_at, updated_at
				) values (:id, :userId, :specId, :slotId, :creditId,
				 'REQUESTED', 'IN_APP_CHAT', :startAt, :endAt, 'Asia/Ho_Chi_Minh',
				 :requestedAt, :deadlineAt, :key, :createdAt, :createdAt)
				""").param("id", completedApptId).param("userId", userId).param("specId", specialistId)
				.param("creditId", creditId).param("slotId", completedSlotId)
				.param("startAt", Timestamp.from(completedStart)).param("endAt", Timestamp.from(completedStart.plusSeconds(3600)))
				.param("requestedAt", Timestamp.from(completedStart.minusSeconds(1800)))
				.param("deadlineAt", Timestamp.from(completedStart.minusSeconds(900)))
				.param("key", "app-completed-" + completedApptId)
				.param("createdAt", Timestamp.from(completedStart.minusSeconds(1800))).update();

		jdbc.sql("""
				update appointment set status='COMPLETED', decided_at=:decidedAt, decision_reason='SPECIALIST_ACCEPTED',
				 session_outcome='COMPLETED', session_outcome_reason='EVIDENCE_REQUIREMENTS_MET', session_policy_version='chat-session-completion-v1',
				 session_ended_at=:endAt, session_settled_at=:endAt, completion_fact_id=:fact where id=:id
				""").param("id", completedApptId).param("decidedAt", Timestamp.from(completedStart.minusSeconds(600)))
				.param("endAt", Timestamp.from(completedStart.plusSeconds(3600)))
				.param("fact", completedFact).update();

		jdbc.sql("update service_credit set state='CONSUMED', appointment_id=:appId where id=:creditId")
				.param("appId", completedApptId).param("creditId", creditId).update();

		// Earning for the completed appointment
		var earningId = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_earning (
				 id, appointment_id, completion_fact_id, consumed_credit_id, specialist_account_id,
				 plan_version, currency, credit_allocation_minor, specialist_share_bps,
				 specialist_amount_minor, platform_allocation_minor, idempotency_source,
				 status, earned_at, settlement_available_at, paid_at, created_at, updated_at
				) values (:id, :appId, :fact, :creditId, :spec, 'v1', 'VND', 200000, 5000, 100000, 100000, :src, 'PAID', now(), now(), now(), now(), now())
				""").param("id", earningId).param("appId", completedApptId).param("fact", completedFact)
				.param("creditId", creditId).param("spec", specialistId).param("src", "src-" + earningId).update();

		// Payout for the earning
		var destId = UUID.randomUUID();
		var payoutId = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_payout_destination (id, specialist_account_id, payout_provider, destination_type, destination_ciphertext, encryption_key_version, destination_fingerprint, display_hint, status, verified_at, created_at, updated_at)
				values (:id, :spec, 'MOMO', 'MOMO_WALLET', 'cipher', 'v1', 'fp-trunc', '0919***', 'VERIFIED', now(), now(), now())
				""").param("id", destId).param("spec", specialistId).update();

		jdbc.sql("""
				insert into specialist_payout (id, specialist_account_id, destination_id, amount_minor, currency, status, payout_provider, idempotency_key, requested_at, requested_on, completed_at, created_at, updated_at)
				values (:id, :spec, :dest, 100000, 'VND', 'SUCCEEDED', 'MOMO', :k, now(), current_date, now(), now(), now())
				""").param("id", payoutId).param("spec", specialistId).param("dest", destId).param("k", "payout-" + payoutId).update();

		jdbc.sql("insert into specialist_payout_item (payout_id, earning_id, amount_minor) values (:payoutId, :earn, 100000)")
				.param("payoutId", payoutId).param("earn", earningId).update();

		// Query provenance by creditId: 101 appointments exist.
		// Truncation must be reported (truncated = true), BUT the consuming completed appointment, its earning and payout MUST be preserved!
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("creditId", creditId.toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.truncated").value(true))
				.andExpect(jsonPath("$.appointments.length()").value(100))
				.andExpect(jsonPath("$.appointments[0].appointmentId").value(completedApptId.toString()))
				.andExpect(jsonPath("$.appointments[0].status").value("COMPLETED"))
				.andExpect(jsonPath("$.earnings.length()").value(1))
				.andExpect(jsonPath("$.earnings[0].earningId").value(earningId.toString()))
				.andExpect(jsonPath("$.payouts.length()").value(1))
				.andExpect(jsonPath("$.payouts[0].payoutId").value(payoutId.toString()));
	}

	@Test
	void paymentProvenanceDistinguishesPurchaseCreditsFromUpgradeCredits() throws Exception {
		var userId = UUID.randomUUID();
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();

		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Spec Purchase Upgrade', 'Bio', 5, 'Asia/Ho_Chi_Minh', 'APPROVED', now(), now(), :admin)
				""").param("id", specialistId).param("admin", adminId).update();

		// Step 1: PURCHASE PLUS via billing service
		var purchaseCheckout = billing.checkout(userId, PLUS, "prov-test-purchase-" + UUID.randomUUID());
		var p1 = purchaseCheckout.paymentId();
		var subId = purchaseCheckout.subscriptionId();
		var t1 = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		var t2 = t1.plusSeconds(15);
		var t3 = java.time.OffsetDateTime.ofInstant(t1, java.time.ZoneOffset.UTC).plusMonths(1).toInstant();
		var upgradedEnd = java.time.OffsetDateTime.ofInstant(t2, java.time.ZoneOffset.UTC).plusMonths(1).toInstant();

		jdbc.sql("update payment_transaction set status='SUCCEEDED', paid_at=:paidAt where id=:id")
				.param("paidAt", Timestamp.from(t1)).param("id", p1).update();
		jdbc.sql("update user_subscription set status='ACTIVE', current_period_start=:start, current_period_end=:end where id=:id")
				.param("start", Timestamp.from(t1)).param("end", Timestamp.from(t3)).param("id", subId).update();

		// Provision C1 (simulating ServiceCreditService flow)
		var period1 = UUID.randomUUID();
		var c1 = UUID.randomUUID();
		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_allocation_minor, created_at, updated_at
				) values (:id, :userId, :planVersion, 'consultation-credit-v1', 'PLUS', 'PAID', :ref, :start, :end, 1, 200000, now(), now())
				""").param("id", period1).param("userId", userId).param("planVersion", PLUS.toString())
				.param("ref", subId.toString()).param("start", Timestamp.from(t1))
				.param("end", Timestamp.from(t3)).update();
		jdbc.sql("insert into service_credit (id, period_id, ordinal, state, created_at, updated_at) values (:id, :periodId, 1, 'AVAILABLE', now(), now())")
				.param("id", c1).param("periodId", period1).update();

		// Verify P1 owns C1
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("paymentId", p1.toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.credits.length()").value(1))
				.andExpect(jsonPath("$.credits[0].creditId").value(c1.toString()));

		// Step 2: Initiate UPGRADE to PREMIUM - capture old period
		var oldStart = jdbc.sql("select current_period_start from user_subscription where id=:id").param("id", subId).query(Timestamp.class).single().toInstant();
		var oldEnd = jdbc.sql("select current_period_end from user_subscription where id=:id").param("id", subId).query(Timestamp.class).single().toInstant();
		assertThat(oldStart).isEqualTo(t1);
		assertThat(oldEnd).isEqualTo(t3);
		assertThat(t2).isBefore(oldEnd);

		var premiumPlanVersionId = UUID.fromString("62200000-0000-4000-8000-000000000003");
		var upgradeId = UUID.randomUUID();
		var p2 = UUID.randomUUID();
		var failedPaymentId = UUID.randomUUID();
		jdbc.sql("""
				insert into payment_transaction (
				 id, subscription_id, target_plan_version_id, payment_provider, momo_order_id, momo_request_id,
				 amount_minor, currency, status, purpose, idempotency_key, failed_at, expires_at, checkout_url, created_at, updated_at
				) values (
				 :id, :subId, :planId, 'MOMO', :orderId, :reqId,
				 150000, 'VND', 'FAILED', 'UPGRADE', :key, :failedAt, now() + interval '1 hour', :checkoutUrl, now(), now()
				)
				""").param("id", failedPaymentId).param("subId", subId).param("planId", premiumPlanVersionId)
				.param("orderId", "order-" + failedPaymentId).param("reqId", "req-" + failedPaymentId)
				.param("key", "idem-failed-" + failedPaymentId).param("failedAt", Timestamp.from(t1.plusSeconds(5)))
				.param("checkoutUrl", "https://test.momo.vn/checkout/" + failedPaymentId).update();
		jdbc.sql("""
				insert into subscription_upgrade (
				 id, subscription_id, from_plan_version_id, to_plan_version_id, payment_id,
				 old_period_start, old_period_end, total_period_seconds, remaining_period_seconds,
				 remaining_feature_value_minor, available_credit_value_minor, offset_minor, amount_due_minor,
				 currency, status, idempotency_key, quoted_at, quote_expires_at, created_at, updated_at, pricing_policy_version
				) values (
				 :id, :subId, :fromPlan, :toPlan, :paymentId,
				 :oldStart, :oldEnd, 2592000, 2160000,
				 100000, 200000, 300000, 50000,
				 'VND', 'FAILED', :key, now(), now() + interval '1 hour', now(), now(), 'upgrade-offset-v1'
				)
				""").param("id", UUID.randomUUID()).param("subId", subId)
				.param("fromPlan", PLUS).param("toPlan", premiumPlanVersionId)
				.param("paymentId", failedPaymentId).param("key", "upg-failed-" + failedPaymentId)
				.param("oldStart", Timestamp.from(oldStart)).param("oldEnd", Timestamp.from(oldEnd)).update();
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("paymentId", failedPaymentId.toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.credits").isEmpty())
				.andExpect(jsonPath("$.appointments").isEmpty())
				.andExpect(jsonPath("$.earnings").isEmpty())
				.andExpect(jsonPath("$.payouts").isEmpty());

		jdbc.sql("""
				insert into payment_transaction (
				 id, subscription_id, target_plan_version_id, payment_provider, momo_order_id, momo_request_id,
				 amount_minor, currency, status, purpose, idempotency_key, expires_at, checkout_url, created_at, updated_at
				) values (
				 :id, :subId, :planId, 'MOMO', :orderId, :reqId,
				 150000, 'VND', 'PENDING', 'UPGRADE', :key, now() + interval '1 hour', :checkoutUrl, now(), now()
				)
				""").param("id", p2).param("subId", subId).param("planId", premiumPlanVersionId)
				.param("orderId", "order-" + p2).param("reqId", "req-" + p2).param("key", "idem-upgrade-" + p2)
				.param("checkoutUrl", "https://test.momo.vn/checkout/" + p2).update();

		jdbc.sql("""
				insert into subscription_upgrade (
				 id, subscription_id, from_plan_version_id, to_plan_version_id, payment_id,
				 old_period_start, old_period_end, total_period_seconds, remaining_period_seconds,
				 remaining_feature_value_minor, available_credit_value_minor, offset_minor, amount_due_minor,
				 currency, status, idempotency_key, quoted_at, quote_expires_at, created_at, updated_at, pricing_policy_version)
				values (
				 :id, :subId, :fromPlan, :toPlan, :paymentId,
				 :oldStart, :oldEnd, 2592000, 2160000,
				 100000, 200000, 300000, 50000,
				 'VND', 'PENDING_PAYMENT', :key, now(), now() + interval '1 hour', now(), now(), 'upgrade-offset-v1')
				""").param("id", upgradeId).param("subId", subId)
				.param("fromPlan", PLUS).param("toPlan", premiumPlanVersionId)
				.param("paymentId", p2).param("key", "upg-idem-" + upgradeId)
				.param("oldStart", Timestamp.from(oldStart))
				.param("oldEnd", Timestamp.from(oldEnd)).update();

		jdbc.sql("insert into subscription_upgrade_credit (upgrade_id, credit_id, allocated_value_minor) values (:upgradeId, :creditId, 200000)")
				.param("upgradeId", upgradeId).param("creditId", c1).update();
		jdbc.sql("update service_credit set state='UPGRADE_HELD' where id=:id").param("id", c1).update();
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("paymentId", p2.toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.credits").isEmpty())
				.andExpect(jsonPath("$.earnings").isEmpty())
				.andExpect(jsonPath("$.payouts").isEmpty());

		// Step 3: Complete P2 (UPGRADE succeeds)
		jdbc.sql("update payment_transaction set status='SUCCEEDED', paid_at=:paidAt where id=:id")
				.param("paidAt", Timestamp.from(t2)).param("id", p2).update();
		jdbc.sql("update subscription_upgrade set status='APPLIED', applied_at=:appliedAt where id=:id")
				.param("appliedAt", Timestamp.from(t2)).param("id", upgradeId).update();
		jdbc.sql("update service_credit set state='REVOKED' where id=:id").param("id", c1).update();
		jdbc.sql("update user_subscription set plan_version_id=:planId, current_period_start=:start, current_period_end=:end where id=:id")
				.param("planId", premiumPlanVersionId).param("start", Timestamp.from(t2))
				.param("end", Timestamp.from(upgradedEnd)).param("id", subId).update();

		// The upgraded period begins before the original PLUS period expires.
		var period2 = UUID.randomUUID();
		var c2a = UUID.randomUUID();
		var c2b = UUID.randomUUID();
		var c2c = UUID.randomUUID();
		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, credit_allocation_minor, created_at, updated_at
				) values (:id, :userId, :planVersion, 'consultation-credit-v1', 'PREMIUM', 'PAID', :ref, :start, :end, 3, 300000, now(), now())
				""").param("id", period2).param("userId", userId)
				.param("planVersion", premiumPlanVersionId.toString()).param("ref", subId.toString())
				.param("start", Timestamp.from(t2)).param("end", Timestamp.from(upgradedEnd)).update();
		jdbc.sql("insert into service_credit (id, period_id, ordinal, state, created_at, updated_at) values (:id, :periodId, 1, 'AVAILABLE', now(), now())")
				.param("id", c2a).param("periodId", period2).update();
		jdbc.sql("insert into service_credit (id, period_id, ordinal, state, created_at, updated_at) values (:id, :periodId, 2, 'AVAILABLE', now(), now())")
				.param("id", c2b).param("periodId", period2).update();
		jdbc.sql("insert into service_credit (id, period_id, ordinal, state, created_at, updated_at) values (:id, :periodId, 3, 'AVAILABLE', now(), now())")
				.param("id", c2c).param("periodId", period2).update();
		assertThat(jdbc.sql("select plan_version from service_credit_period where id=:id")
				.param("id", period1).query(String.class).single()).isEqualTo(PLUS.toString());
		assertThat(jdbc.sql("select plan_version from service_credit_period where id=:id")
				.param("id", period2).query(String.class).single()).isEqualTo(premiumPlanVersionId.toString());
		assertThat(jdbc.sql("select period_start from service_credit_period where id=:id")
				.param("id", period2).query(Timestamp.class).single().toInstant()).isEqualTo(t2);
		assertThat(jdbc.sql("select paid_at from payment_transaction where id=:id")
				.param("id", p2).query(Timestamp.class).single().toInstant()).isEqualTo(t2);

		// CRITICAL: P1 still owns C1 (even though revoked)
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("paymentId", p1.toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.credits.length()").value(1))
				.andExpect(jsonPath("$.credits[0].creditId").value(c1.toString()));

		// CRITICAL: P2 owns C2a, C2b, C2c (NOT C1)
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("paymentId", p2.toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.credits.length()").value(3))
				.andExpect(jsonPath("$.credits[?(@.creditId == '" + c2a.toString() + "')]").exists())
				.andExpect(jsonPath("$.credits[?(@.creditId == '" + c2b.toString() + "')]").exists())
				.andExpect(jsonPath("$.credits[?(@.creditId == '" + c2c.toString() + "')]").exists());

		// Subscription shows all historical credits
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("subscriptionId", subId.toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.payments.length()").value(3))
				.andExpect(jsonPath("$.credits.length()").value(4));

		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("paymentId", UUID.randomUUID().toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(false))
				.andExpect(jsonPath("$.credits").isEmpty());
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor role(UUID id, String authority) {
		return jwt().jwt(token -> token.subject(id.toString())).authorities(new SimpleGrantedAuthority(authority));
	}
}

package com.mentalbridge.consultation.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.mentalbridge.consultation.shared.ApiException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SubscriptionBillingIntegrationTests extends ConsultationTestProperties {

	private static final UUID PLUS = UUID.fromString("62200000-0000-4000-8000-000000000002");
	private static final UUID PREMIUM = UUID.fromString("62200000-0000-4000-8000-000000000003");
	private static final String PARTNER = "MOMO_TEST_PARTNER";
	private static final String ACCESS_KEY = "test-payment-access-key";
	private static final String SECRET_KEY = "test-payment-secret-key";

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired SubscriptionBillingService billing;
	@Autowired com.mentalbridge.consultation.credits.ServiceCreditService credits;

	@Test
	void preservesRetiredHistoricalPricesAndRejectsNewPurchaseOfThoseVersions() {
		var historical = UUID.fromString("51500000-0000-4000-8000-000000000002");
		assertThat(jdbc.sql("select price_minor from subscription_plan_version where id=:id")
				.param("id", historical).query(Long.class).single()).isEqualTo(1_390_000);
		assertThat(jdbc.sql("select pricing_policy_version from subscription_plan_version where id=:id")
				.param("id", historical).query(String.class).single()).isEqualTo("upgrade-offset-v1");
		assertThatThrownBy(() -> billing.checkout(UUID.randomUUID(), historical, key("retired")))
				.isInstanceOfSatisfying(ApiException.class,
						error -> assertThat(error.code()).isEqualTo("PLAN_VERSION_NOT_AVAILABLE"));
	}

	@Test
	void snapshotsPurchasedVersionAndAllocationAndNeverReprovisionsOnReload() throws Exception {
		var userId = UUID.randomUUID();
		var payment = billing.checkout(userId, PLUS, key("provenance"));
		var success = fixture(payment.paymentId(), 0, Instant.now().toEpochMilli(), 850001);
		sign(success);
		postIpn(success, 204);
		credits.current(userId);
		credits.current(userId);
		assertThat(jdbc.sql("select plan_version from service_credit_period where account_id=:id")
				.param("id", userId).query(String.class).single()).isEqualTo(PLUS.toString());
		assertThat(jdbc.sql("select credit_allocation_minor from service_credit_period where account_id=:id")
				.param("id", userId).query(Long.class).single()).isEqualTo(300_000);
		assertThat(jdbc.sql("select count(*) from service_credit_ledger where account_id=:id and event_type='PROVISIONED'")
				.param("id", userId).query(Long.class).single()).isEqualTo(4);
	}

	@Test
	void exposesOnlyTheAuthoritativeVersionedVndCatalogue() throws Exception {
		var userId = UUID.randomUUID();
		mvc.perform(get("/api/v1/service-plans").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.checkoutEnabled").value(true))
				.andExpect(jsonPath("$.paymentProvider").value("MOMO"))
				.andExpect(jsonPath("$.plans.length()").value(3))
				.andExpect(jsonPath("$.plans[0].planCode").value("FREE"))
				.andExpect(jsonPath("$.plans[0].priceVnd").value(0))
				.andExpect(jsonPath("$.plans[0].consultationCredits").value(0))
				.andExpect(jsonPath("$.plans[0].maxActiveReservations").value(0))
				.andExpect(jsonPath("$.plans[1].planCode").value("PLUS"))
				.andExpect(jsonPath("$.plans[1].priceVnd").value(5_000))
				.andExpect(jsonPath("$.plans[1].pricingPolicyVersion").value("sandbox-full-price-v1"))
				.andExpect(jsonPath("$.plans[1].consultationCredits").value(4))
				.andExpect(jsonPath("$.plans[1].maxActiveReservations").value(2))
				.andExpect(jsonPath("$.plans[1].creditAllocationVnd").value(300_000))
				.andExpect(jsonPath("$.plans[1].allowedUpgrades[0]").value("PREMIUM"))
				.andExpect(jsonPath("$.plans[2].priceVnd").value(10_000))
				.andExpect(jsonPath("$.plans[2].consultationCredits").value(10))
				.andExpect(jsonPath("$.plans[2].maxActiveReservations").value(4));
		mvc.perform(get("/api/v1/service-plans")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/service-plans").with(role(userId, "ROLE_SPECIALIST")))
				.andExpect(status().isForbidden());
	}

	@Test
	void signedSuccessActivatesPurchaseAndGrantsCreditsExactlyOnce() throws Exception {
		var userId = UUID.randomUUID();
		var commandKey = key("purchase");
		var payment = billing.checkout(userId, PLUS, commandKey);
		assertThat(payment.amountVnd()).isEqualTo(5_000);
		assertThat(payment.pricingPolicyVersion()).isEqualTo("sandbox-full-price-v1");
		assertThat(payment.provider()).isEqualTo("FAKE");
		assertThat(payment.checkoutUrl()).startsWith("https://test-payment.momo.vn/");
		assertThat(billing.checkout(userId, PLUS, commandKey)).isEqualTo(payment);

		var invalid = fixture(payment.paymentId(), 0, Instant.now().toEpochMilli(), 800001);
		invalid.put("signature", "0".repeat(64));
		mvc.perform(post("/internal/v1/payments/momo/ipn").contentType("application/json")
				.content(json.writeValueAsBytes(invalid))).andExpect(status().isUnauthorized());
		assertThat(receiptCount(payment.paymentId())).isZero();

		var success = fixture(payment.paymentId(), 0, Instant.now().plusSeconds(1).toEpochMilli(), 800002);
		success.put("paymentOption", "momo");
		success.put("userFee", 0);
		success.put("ignoredProviderField", "discarded");
		sign(success);
		postIpn(success, 204);
		postIpn(success, 204);

		assertThat(billing.payment(userId, payment.paymentId()).status()).isEqualTo("SUCCEEDED");
		assertThat(receiptCount(payment.paymentId())).isOne();
		assertThat(jdbc.sql("select status from user_subscription where id=:id")
				.param("id", payment.subscriptionId()).query(String.class).single()).isEqualTo("ACTIVE");
		assertThat(jdbc.sql("select count(*) from service_credit c join service_credit_period p on p.id=c.period_id where p.account_id=:id")
				.param("id", userId).query(Long.class).single()).isEqualTo(4);
		assertThat(jdbc.sql("select count(*) from service_credit_ledger where account_id=:id and event_type='PROVISIONED'")
				.param("id", userId).query(Long.class).single()).isEqualTo(4);
	}

	@Test
	void pendingFailureAndLaterSuccessKeepTheFirstTerminalOutcome() throws Exception {
		var userId = UUID.randomUUID();
		var payment = billing.checkout(userId, PLUS, key("finality"));
		var base = Instant.now().toEpochMilli();
		var pending = fixture(payment.paymentId(), 9000, base, 810001);
		sign(pending);
		postIpn(pending, 204);
		assertThat(billing.payment(userId, payment.paymentId()).status()).isEqualTo("PENDING");

		var failed = fixture(payment.paymentId(), 1006, base + 1, 810002);
		sign(failed);
		postIpn(failed, 204);
		assertThat(billing.payment(userId, payment.paymentId()).status()).isEqualTo("FAILED");

		var lateSuccess = fixture(payment.paymentId(), 0, base + 2, 810003);
		sign(lateSuccess);
		postIpn(lateSuccess, 204);
		assertThat(billing.payment(userId, payment.paymentId()).status()).isEqualTo("FAILED");
		assertThat(jdbc.sql("select failure_code from momo_payment_ipn where trans_id=810003")
				.query(String.class).single()).isEqualTo("PAYMENT_ALREADY_FINAL");
	}

	@Test
	void acknowledgesVerifiedMismatchesWithoutMutatingThePaymentAndRejectsMissingFields() throws Exception {
		var userId = UUID.randomUUID();
		var payment = billing.checkout(userId, PLUS, key("mismatch"));
		var base = Instant.now().toEpochMilli();
		var amount = fixture(payment.paymentId(), 0, base, 820001);
		amount.put("amount", amount.get("amount").longValue() + 1);
		sign(amount);
		postIpn(amount, 204);
		var request = fixture(payment.paymentId(), 0, base + 1, 820002);
		request.put("requestId", UUID.randomUUID().toString());
		sign(request);
		postIpn(request, 204);
		var order = fixture(payment.paymentId(), 0, base + 2, 820003);
		order.put("orderId", "unknown-order");
		sign(order);
		postIpn(order, 204);
		var partner = fixture(payment.paymentId(), 0, base + 3, 820004);
		partner.put("partnerCode", "OTHER_PARTNER");
		sign(partner);
		postIpn(partner, 204);

		assertThat(billing.payment(userId, payment.paymentId()).status()).isEqualTo("PENDING");
		assertThat(jdbc.sql("select failure_code from momo_payment_ipn where processing_status='VERIFIED_UNMATCHED'")
				.query(String.class).list()).contains("AMOUNT_MISMATCH", "REQUEST_MISMATCH", "ORDER_NOT_FOUND", "PARTNER_MISMATCH");

		var missing = fixture(payment.paymentId(), 0, base + 4, 820005);
		missing.remove("payType");
		postIpn(missing, 400);
	}

	@Test
	void expiredSuccessFailsSafelyAndPlusCanUpgradeOnlyToPremium() throws Exception {
		var expiredUser = UUID.randomUUID();
		var expired = billing.checkout(expiredUser, PLUS, key("expired"));
		jdbc.sql("update payment_transaction set created_at=now()-interval '20 minutes', expires_at=now()-interval '1 second' where id=:id")
				.param("id", expired.paymentId()).update();
		var expiredSuccess = fixture(expired.paymentId(), 0, Instant.now().toEpochMilli(), 830001);
		sign(expiredSuccess);
		postIpn(expiredSuccess, 204);
		assertThat(billing.payment(expiredUser, expired.paymentId()).status()).isEqualTo("FAILED");

		var userId = UUID.randomUUID();
		var plus = billing.checkout(userId, PLUS, key("plus"));
		var plusSuccess = fixture(plus.paymentId(), 0, Instant.now().plusSeconds(1).toEpochMilli(), 830002);
		sign(plusSuccess);
		postIpn(plusSuccess, 204);
		var upgrade = billing.checkout(userId, PREMIUM, key("upgrade"));
		assertThat(upgrade.purpose()).isEqualTo("UPGRADE");
		assertThat(upgrade.amountVnd()).isEqualTo(10_000L);
		assertThat(jdbc.sql("select offset_minor from subscription_upgrade where payment_id=:id")
				.param("id", upgrade.paymentId()).query(Long.class).single()).isZero();
		assertThat(jdbc.sql("select available_credit_value_minor from subscription_upgrade where payment_id=:id")
				.param("id", upgrade.paymentId()).query(Long.class).single()).isEqualTo(1_200_000L);
		assertThat(jdbc.sql("select count(*) from service_credit where state='UPGRADE_HELD'")
				.query(Long.class).single()).isEqualTo(4);
		var premiumSuccess = fixture(upgrade.paymentId(), 0, Instant.now().plusSeconds(2).toEpochMilli(), 830003);
		sign(premiumSuccess);
		postIpn(premiumSuccess, 204);
		assertThat(jdbc.sql("select package_code from current_service_entitlement where account_id=:id")
				.param("id", userId).query(String.class).single()).isEqualTo("PREMIUM");
		assertThat(jdbc.sql("select count(*) from service_credit c join service_credit_period p on p.id=c.period_id where p.account_id=:id and c.state='AVAILABLE'")
				.param("id", userId).query(Long.class).single()).isEqualTo(10);
		assertThat(jdbc.sql("select count(*) from service_credit c join service_credit_period p on p.id=c.period_id where p.account_id=:id and c.state='REVOKED'")
				.param("id", userId).query(Long.class).single()).isEqualTo(4);
		assertThatThrownBy(() -> billing.checkout(userId, PLUS, key("downgrade")))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("SUBSCRIPTION_DOWNGRADE_NOT_SUPPORTED"));
	}

	@Test
	void expiresAbandonedPurchasesAndReleasesUpgradeCreditsWithoutAnIpn() throws Exception {
		var abandonedUser = UUID.randomUUID();
		var abandoned = billing.checkout(abandonedUser, PLUS, key("abandoned"));
		expire(abandoned.paymentId());
		billing.expirePendingCheckouts();
		assertThat(billing.payment(abandonedUser, abandoned.paymentId()).status()).isEqualTo("FAILED");
		assertThat(jdbc.sql("select status from user_subscription where id=:id")
				.param("id", abandoned.subscriptionId()).query(String.class).single()).isEqualTo("ENDED");

		var upgradingUser = UUID.randomUUID();
		var plus = billing.checkout(upgradingUser, PLUS, key("expiry-plus"));
		var success = fixture(plus.paymentId(), 0, Instant.now().toEpochMilli(), 840001);
		sign(success);
		postIpn(success, 204);
		var upgrade = billing.checkout(upgradingUser, PREMIUM, key("abandoned-upgrade"));
		expire(upgrade.paymentId());
		billing.expirePendingCheckouts();

		assertThat(billing.payment(upgradingUser, upgrade.paymentId()).status()).isEqualTo("FAILED");
		assertThat(jdbc.sql("select status from subscription_upgrade where payment_id=:id")
				.param("id", upgrade.paymentId()).query(String.class).single()).isEqualTo("EXPIRED");
		assertThat(jdbc.sql("""
				select count(*) from service_credit c join service_credit_period p on p.id=c.period_id
				where p.account_id=:id and c.state='AVAILABLE'
				""").param("id", upgradingUser).query(Long.class).single()).isEqualTo(4);
	}

	private ObjectNode fixture(UUID paymentId, int resultCode, long responseTime, long transId) {
		var values = jdbc.sql("select momo_order_id, momo_request_id, amount_minor from payment_transaction where id=:id")
				.param("id", paymentId).query((row, ignored) -> new String[] { row.getString(1), row.getString(2), row.getString(3) }).single();
		var node = json.createObjectNode();
		node.put("partnerCode", PARTNER);
		node.put("orderId", values[0]);
		node.put("requestId", values[1]);
		node.put("amount", Long.parseLong(values[2]));
		node.put("orderInfo", "MentalBridge plan checkout");
		node.put("orderType", "momo_wallet");
		node.put("transId", transId);
		node.put("resultCode", resultCode);
		node.put("message", resultCode == 0 ? "Successful." : "Provider result");
		node.put("payType", "qr");
		node.put("responseTime", responseTime);
		node.put("extraData", "");
		node.put("signature", "0".repeat(64));
		return node;
	}

	private void sign(ObjectNode node) throws Exception {
		var raw = "accessKey=" + ACCESS_KEY + "&amount=" + node.get("amount").longValue()
				+ "&extraData=" + node.get("extraData").textValue() + "&message=" + node.get("message").textValue()
				+ "&orderId=" + node.get("orderId").textValue() + "&orderInfo=" + node.get("orderInfo").textValue()
				+ "&orderType=" + node.get("orderType").textValue() + "&partnerCode=" + node.get("partnerCode").textValue()
				+ "&payType=" + node.get("payType").textValue() + "&requestId=" + node.get("requestId").textValue()
				+ "&responseTime=" + node.get("responseTime").longValue() + "&resultCode=" + node.get("resultCode").intValue()
				+ "&transId=" + node.get("transId").longValue();
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		node.put("signature", HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))));
	}

	private void postIpn(ObjectNode payload, int expectedStatus) throws Exception {
		mvc.perform(post("/internal/v1/payments/momo/ipn").contentType("application/json")
				.content(json.writeValueAsBytes(payload))).andExpect(status().is(expectedStatus));
	}

	private long receiptCount(UUID paymentId) {
		return jdbc.sql("select count(*) from momo_payment_ipn where payment_id=:id")
				.param("id", paymentId).query(Long.class).single();
	}

	private void expire(UUID paymentId) {
		jdbc.sql("""
				update payment_transaction set created_at=now()-interval '20 minutes',
				 expires_at=now()-interval '1 second' where id=:id
				""")
				.param("id", paymentId).update();
	}

	private String key(String prefix) { return prefix + "-" + UUID.randomUUID(); }
	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID id) { return role(id, "ROLE_USER"); }
	private org.springframework.test.web.servlet.request.RequestPostProcessor role(UUID id, String authority) {
		return jwt().jwt(token -> token.subject(id.toString())).authorities(new SimpleGrantedAuthority(authority));
	}
}

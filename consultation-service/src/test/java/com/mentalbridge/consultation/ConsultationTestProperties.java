package com.mentalbridge.consultation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.mentalbridge.consultation.configuration.IdentityAccountStatusVerifier;

public abstract class ConsultationTestProperties {

	protected static final String EVIDENCE_SERVICE_TOKEN = "test-consultation-evidence-token-32-characters";
	private static final KeyPair JWT_KEY_PAIR = keyPair();

	@MockitoBean
	protected IdentityAccountStatusVerifier accountStatusVerifier;

	@BeforeEach
	protected void defaultActiveAccountStatus() {
		when(accountStatusVerifier.isActive(any(), any())).thenReturn(true);
	}

	static {
		System.setProperty("user.timezone", "UTC");
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
	}

	@DynamicPropertySource
	static void consultationProperties(DynamicPropertyRegistry properties) {
		properties.add("mentalbridge.consultation.jwt.issuer", () -> "https://identity.test.mentalbridge");
		properties.add("mentalbridge.consultation.jwt.audience", () -> "mentalbridge-test-api");
		properties.add("mentalbridge.consultation.jwt.public-key",
				() -> Base64.getEncoder().encodeToString(JWT_KEY_PAIR.getPublic().getEncoded()));
		properties.add("mentalbridge.consultation.identity-account-status.connect-timeout", () -> "PT0.25S");
		properties.add("mentalbridge.consultation.identity-account-status.read-timeout", () -> "PT0.75S");
		properties.add("mentalbridge.consultation.evidence-service-token", () -> EVIDENCE_SERVICE_TOKEN);
		properties.add("mentalbridge.consultation.account-lifecycle.enabled", () -> "false");
		properties.add("mentalbridge.consultation.video.demo-immediate-access-enabled", () -> "false");
		properties.add("mentalbridge.consultation.payout.encryption-key",
				() -> Base64.getEncoder().encodeToString("test-payout-key-32-bytes-long!!!".getBytes()));
		properties.add("mentalbridge.consultation.payout.mode", () -> "FAKE");
		properties.add("mentalbridge.consultation.billing.mode", () -> "FAKE");
		properties.add("mentalbridge.consultation.billing.momo-partner-code", () -> "MOMO_TEST_PARTNER");
		properties.add("mentalbridge.consultation.billing.momo-access-key", () -> "test-payment-access-key");
		properties.add("mentalbridge.consultation.billing.momo-secret-key", () -> "test-payment-secret-key");
		properties.add("mentalbridge.consultation.billing.momo-signature-key-version", () -> "test-v1");
	}

	private static KeyPair keyPair() {
		try {
			var generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (Exception exception) {
			throw new IllegalStateException("Unable to create test JWT key", exception);
		}
	}

	protected static String signedTestToken(UUID subject, String role) throws Exception {
		if (!java.util.Set.of("USER", "SPECIALIST", "ADMIN").contains(role)) throw new IllegalArgumentException("Unknown test role");
		var encoder = Base64.getUrlEncoder().withoutPadding();
		var header = encoder.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		var claims = "{\"iss\":\"https://identity.test.mentalbridge\",\"aud\":[\"mentalbridge-test-api\"],\"sub\":\"%s\",\"roles\":[\"%s\"],\"exp\":%d}"
				.formatted(subject, role, java.time.Instant.now().plusSeconds(40 * 86_400L).getEpochSecond());
		var payload = header + "." + encoder.encodeToString(claims.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		var signature = java.security.Signature.getInstance("SHA256withRSA");
		signature.initSign(JWT_KEY_PAIR.getPrivate());
		signature.update(payload.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		return payload + "." + encoder.encodeToString(signature.sign());
	}

	protected void establishPaidEntitlement(JdbcClient jdbc, UUID accountId) {
		var subscriptionId = UUID.randomUUID();
		var paymentId = UUID.randomUUID();
		jdbc.sql("""
				insert into user_subscription(id,user_id,plan_version_id,status,current_period_start,current_period_end,
				 idempotency_key,created_at,updated_at)
				select :id,e.account_id,v.id,'ACTIVE',e.effective_from,e.effective_until,:key,e.effective_from,e.effective_from
				from current_service_entitlement e join subscription_plan_version v
				 on v.plan_code=e.package_code and v.retired_at is null
				where e.account_id=:accountId and e.source='PAID'
				""").param("id", subscriptionId).param("accountId", accountId)
				.param("key", "paid-fixture-" + subscriptionId).update();
		jdbc.sql("""
				insert into payment_transaction(id,subscription_id,target_plan_version_id,payment_provider,momo_order_id,
				 momo_request_id,amount_minor,currency,status,purpose,idempotency_key,paid_at,expires_at,checkout_url,
				 created_at,updated_at)
				select :id,s.id,v.id,'FAKE',:orderId,:requestId,v.price_minor,'VND','SUCCEEDED','PURCHASE',:key,
				 s.current_period_start,s.current_period_start+interval '20 minutes','https://test-payment.momo.vn/paid-fixture',
				 s.current_period_start,s.current_period_start
				from user_subscription s join subscription_plan_version v on v.id=s.plan_version_id where s.id=:subscriptionId
				""").param("id", paymentId).param("subscriptionId", subscriptionId)
				.param("orderId", "paid-fixture-order-" + paymentId).param("requestId", "paid-fixture-request-" + paymentId)
				.param("key", "paid-fixture-" + paymentId).update();
		jdbc.sql("update current_service_entitlement set source_reference=:source where account_id=:accountId")
				.param("source", subscriptionId.toString()).param("accountId", accountId).update();
	}
}

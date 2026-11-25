package com.mentalbridge.consultation.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BillingPropertiesTests {

	@Test
	void payosTestCatalogueRequiresExplicitStagingAndCollectConfigurationOnly() {
		var properties = payosProperties();
		assertThat(properties.payosReady()).isTrue();
		assertThat(properties.checkoutEnabled()).isTrue();
		assertThat(properties.sandboxCheckoutAllowed()).isTrue();
		assertThat(properties.isProductionApproved()).isFalse();
		var environment = new MockEnvironment();
		properties.setEnvironment(environment);
		assertThat(properties.payosReady()).isFalse();
		for (var profile : java.util.List.of("dev", "test", "prod", "production")) {
			environment.setActiveProfiles(profile);
			assertThat(properties.payosReady()).isFalse();
		}
		environment.setActiveProfiles("staging", "production");
		assertThat(properties.payosReady()).isFalse();
		environment.setActiveProfiles("staging");
		properties.setPayosChecksumKey("");
		assertThat(properties.checkoutEnabled()).isFalse();
	}

	@Test
	void payosRequiresApprovedHttpsOriginsAndRejectsCredentialOrHostConfusion() {
		for (var url : java.util.List.of("http://staging.mentalbridge.online/return",
				"https://mentalbridge.online/return", "https://staging.mentalbridge.online.evil.invalid/return",
				"https://user@staging.mentalbridge.online/return", "https://staging.mentalbridge.online:8443/return",
				"https://staging.mentalbridge.online/return#fragment", "")) {
			var properties = payosProperties();
			properties.setPayosReturnUrl(url);
			assertThat(properties.payosReady()).as(url).isFalse();
		}
		for (var url : java.util.List.of("https://api-merchant.payos.vn.evil.invalid",
				"https://api-merchant.payos.vn/proxy", "https://api-merchant.payos.vn?redirect=1", "http://api-merchant.payos.vn")) {
			var properties = payosProperties();
			properties.setPayosBaseUrl(url);
			assertThat(properties.payosReady()).as(url).isFalse();
		}
	}

	static BillingProperties payosProperties() {
		var properties = new BillingProperties();
		var environment = new MockEnvironment();
		environment.setActiveProfiles("staging");
		properties.setEnvironment(environment);
		properties.setMode("PAYOS");
		properties.setPayosClientId("synthetic-client");
		properties.setPayosApiKey("synthetic-api-key");
		properties.setPayosChecksumKey("synthetic-checksum-key");
		properties.setPayosWebhookUrl("https://staging.mentalbridge.online/internal/v1/payments/payos/webhook");
		properties.setPayosReturnUrl("https://staging.mentalbridge.online/subscription");
		properties.setPayosCancelUrl("https://staging.mentalbridge.online/subscription");
		return properties;
	}

	@Test
	void sandboxPolicyCannotUseProductionCheckoutEvenWithCompleteApprovedCredentials() {
		var properties = new BillingProperties();
		assertThat(properties.checkoutEnabled()).isFalse();
		assertThat(properties.sandboxCheckoutAllowed()).isFalse();
		properties.setMode("FAKE");
		assertThat(properties.sandboxCheckoutAllowed()).isTrue();
		properties.setMode("MOMO");
		properties.setProductionApproved(true);
		properties.setMomoPartnerCode("synthetic-partner");
		properties.setMomoAccessKey("synthetic-access");
		properties.setMomoSecretKey("synthetic-secret");
		properties.setMomoIpnUrl("https://example.invalid/ipn");
		properties.setMomoRedirectUrl("https://example.invalid/return");
		properties.setMomoBaseUrl("https://payment.momo.vn");
		assertThat(properties.momoReady()).isTrue();
		assertThat(properties.sandboxCheckoutAllowed()).isFalse();
		properties.setMomoBaseUrl("https://test-payment.momo.vn");
		assertThat(properties.sandboxCheckoutAllowed()).isTrue();
		properties.setMomoBaseUrl("https://test-payment.momo.vn.evil.invalid");
		assertThat(properties.sandboxCheckoutAllowed()).isFalse();
		properties.setMomoBaseUrl("https://test-payment.momo.vn:8443");
		assertThat(properties.sandboxCheckoutAllowed()).isFalse();
	}
}

package com.mentalbridge.consultation.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BillingPropertiesTests {

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

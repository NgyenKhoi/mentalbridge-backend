package com.mentalbridge.consultation.earnings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PayoutPropertiesTests {

	@Test
	void demoRulesRequireExplicitFlagAndOnlyTheStagingProfile() {
		var properties = new PayoutProperties();
		assertThat(properties.getMinimumWithdrawalVnd()).isEqualTo(100_000);
		assertThat(properties.getSettlementHold()).isEqualTo(Duration.ofDays(7));
		properties.setMinimumWithdrawalVnd(2_000);
		properties.setSettlementHold(Duration.ofMinutes(10));
		var environment = new MockEnvironment();
		properties.setEnvironment(environment);
		environment.setActiveProfiles("staging");
		assertThat(properties.stagingDemoEnabled()).isFalse();
		assertThat(properties.getMinimumWithdrawalVnd()).isEqualTo(100_000);
		assertThat(properties.getSettlementHold()).isEqualTo(Duration.ofDays(7));
		properties.setStagingDemoEnabled(true);
		assertThat(properties.stagingDemoEnabled()).isTrue();
		assertThat(properties.getMinimumWithdrawalVnd()).isEqualTo(2_000);
		assertThat(properties.getSettlementHold()).isEqualTo(Duration.ofMinutes(10));
		for (var profiles : List.of(new String[0], new String[]{"dev"}, new String[]{"test"},
				new String[]{"prod"}, new String[]{"production"}, new String[]{"staging", "prod"},
				new String[]{"staging", "production"})) {
			environment.setActiveProfiles(profiles);
			assertThat(properties.stagingDemoEnabled()).as(String.join(",", profiles)).isFalse();
			assertThat(properties.getMinimumWithdrawalVnd()).isEqualTo(100_000);
			assertThat(properties.getSettlementHold()).isEqualTo(Duration.ofDays(7));
		}
		properties.setEnvironment(null);
		assertThat(properties.stagingDemoEnabled()).isFalse();
		properties.setMinimumWithdrawalVnd(200_000);
		properties.setSettlementHold(Duration.ofDays(14));
		assertThat(properties.getMinimumWithdrawalVnd()).isEqualTo(200_000);
		assertThat(properties.getSettlementHold()).isEqualTo(Duration.ofDays(14));
	}

	@Test
	void configurationRejectsNonpositiveMinimumAndNonpositiveFractionalOrMissingHold() {
		var properties = new PayoutProperties();
		for (var amount : List.of(0L, -1L)) {
			assertThatThrownBy(() -> properties.setMinimumWithdrawalVnd(amount))
					.isInstanceOf(IllegalArgumentException.class);
		}
		for (var hold : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(500),
				Duration.ofMillis(1_500), Duration.ofMillis(600_500))) {
			assertThatThrownBy(() -> properties.setSettlementHold(hold))
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> properties.setSettlementHold(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void realPayosRequiresSeparateCompleteCredentialsEncryptionAndOnlyStaging() {
		var properties = payosProperties();
		assertThat(properties.ready()).isTrue();
		assertThat(properties.isProductionApproved()).isFalse();
		var environment = new MockEnvironment();
		properties.setEnvironment(environment);
		assertThat(properties.ready()).isFalse();
		for (var profile : List.of("dev", "test", "prod", "production")) {
			environment.setActiveProfiles(profile);
			assertThat(properties.ready()).as(profile).isFalse();
		}
		environment.setActiveProfiles("staging", "prod");
		assertThat(properties.ready()).isFalse();
		environment.setActiveProfiles("staging");
		assertThat(properties.ready()).isTrue();
		properties.setPayosClientId("");
		assertThat(properties.ready()).isFalse();
		properties.setPayosClientId("synthetic-client");
		properties.setPayosApiKey("");
		assertThat(properties.ready()).isFalse();
		properties.setPayosApiKey("synthetic-api");
		properties.setPayosChecksumKey("");
		assertThat(properties.ready()).isFalse();
	}

	@Test
	void originAndEncryptionConfigurationFailClosed() {
		for (var origin : List.of("https://api-merchant.payos.vn.evil.invalid", "http://api-merchant.payos.vn",
				"https://api-merchant.payos.vn:8443", "https://user@api-merchant.payos.vn",
				"https://api-merchant.payos.vn/proxy", "https://api-merchant.payos.vn?q=1",
				"https://api-merchant.payos.vn#fragment", "malformed")) {
			var properties = payosProperties();
			properties.setPayosBaseUrl(origin);
			assertThat(properties.ready()).as(origin).isFalse();
		}
		for (var key : List.of("", "invalid-base64", Base64.getEncoder().encodeToString(new byte[16]))) {
			var properties = payosProperties();
			properties.setEncryptionKey(key);
			assertThat(properties.ready()).isFalse();
		}
		var properties = payosProperties();
		properties.setEncryptionKeyVersion("");
		assertThat(properties.ready()).isFalse();
		properties.setEncryptionKeyVersion("v1");
		properties.setPayosBaseUrl("");
		assertThat(properties.ready()).isTrue();
	}

	@Test
	void verificationUsesRetainedProviderCredentialsAndDisabledPreventsEverySubmission() {
		var properties = payosProperties();
		properties.setMode("FAKE");
		assertThat(properties.payosReady()).isFalse();
		assertThat(properties.payosVerificationReady()).isTrue();
		assertThat(properties.submitReady("PAYOS")).isTrue();
		properties.setMode("DISABLED");
		assertThat(properties.ready()).isFalse();
		assertThat(properties.submitReady("PAYOS")).isFalse();
		assertThat(properties.submitReady("FAKE")).isFalse();
		assertThat(properties.verificationReady("PAYOS")).isTrue();
		assertThat(properties.verificationReady("unsupported")).isFalse();
		assertThatThrownBy(() -> properties.setMode("UNRECOGNIZED")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties.setMode(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void fakeRemainsDeterministicAndDisabledNeverFallsBackToFake() {
		var properties = new PayoutProperties();
		var momo = mock(MomoPayoutProvider.class);
		var payos = mock(PayosPayoutProvider.class);
		var provider = new ConfiguredPayoutProvider(properties, momo, payos);
		var command = new PayoutProvider.Command(UUID.randomUUID(), UUID.randomUUID(), "unused", 210_000,
				"VND", "BANK_ACCOUNT", "unused");
		assertThat(provider.submit(command).failureCode()).isEqualTo("REAL_PAYOUT_DISABLED");
		assertThat(provider.query(new PayoutProvider.Query("FAKE", command.payoutId(), command.attemptId(),
				command.requestId(), command.amountVnd(), command.currency(), command.destinationType(),
				command.destinationCiphertext(), null))
				.failureCode()).isEqualTo("PAYOUT_PROVIDER_UNAVAILABLE");
		properties.setMode("FAKE");
		var first = provider.submit(command);
		assertThat(first.status()).isEqualTo("SUCCEEDED");
		assertThat(first.providerReference()).isEqualTo("fake-" + command.payoutId());
		assertThat(provider.submit(command)).isEqualTo(first);
		properties.setMode("DISABLED");
		assertThat(provider.submit(command).failureCode()).isEqualTo("REAL_PAYOUT_DISABLED");
		assertThat(provider.submit("FAKE", command).failureCode()).isEqualTo("REAL_PAYOUT_DISABLED");
		verifyNoInteractions(momo, payos);
	}

	static PayoutProperties payosProperties() {
		var properties = new PayoutProperties();
		var environment = new MockEnvironment();
		environment.setActiveProfiles("staging");
		properties.setEnvironment(environment);
		properties.setMode("PAYOS");
		properties.setPayosClientId("synthetic-payout-client");
		properties.setPayosApiKey("synthetic-payout-api");
		properties.setPayosChecksumKey("synthetic-payout-checksum");
		properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
		return properties;
	}
}

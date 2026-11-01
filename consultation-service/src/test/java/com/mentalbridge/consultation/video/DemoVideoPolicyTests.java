package com.mentalbridge.consultation.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;

import com.mentalbridge.consultation.entitlement.EntitlementSource;

class DemoVideoPolicyTests {

	@Test
	void defaultDisabledDoesNotReadOrGrantDemoOrPaidAccounts() {
		var properties = new LiveKitProperties();
		var jdbc = mock(JdbcClient.class);
		var policy = new DemoVideoPolicy(properties, new MockEnvironment(), jdbc, Clock.systemUTC());
		policy.validateEnvironment();
		assertThat(policy.allowsSource(EntitlementSource.DEMO)).isFalse();
		assertThat(policy.allowsSource(EntitlementSource.PAID)).isFalse();
		assertThat(policy.allowsUser(UUID.randomUUID())).isFalse();
		assertThat(policy.allowsCredit(UUID.randomUUID(), UUID.randomUUID())).isFalse();
		verifyNoInteractions(jdbc);
	}

	@ParameterizedTest
	@ValueSource(strings = { "dev", "local", "test" })
	void explicitDevelopmentOptInGrantsOnlyDemoSource(String profile) {
		var properties = new LiveKitProperties();
		properties.setDemoImmediateAccessEnabled(true);
		var environment = new MockEnvironment();
		environment.setActiveProfiles(profile);
		var policy = new DemoVideoPolicy(properties, environment, mock(JdbcClient.class), Clock.systemUTC());
		policy.validateEnvironment();
		assertThat(policy.allowsSource(EntitlementSource.DEMO)).isTrue();
		assertThat(policy.allowsSource(EntitlementSource.PAID)).isFalse();
		assertThat(policy.allowsSource(EntitlementSource.DEFAULT_FREE)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "prod", "production", "staging", "default" })
	void enabledDemoFailsClosedOutsideExplicitDevelopmentProfiles(String profile) {
		var properties = new LiveKitProperties();
		properties.setDemoImmediateAccessEnabled(true);
		var environment = new MockEnvironment();
		environment.setActiveProfiles(profile);
		var policy = new DemoVideoPolicy(properties, environment, mock(JdbcClient.class), Clock.systemUTC());
		assertThatThrownBy(policy::validateEnvironment).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("staging/production is prohibited");
		assertThat(policy.allowsSource(EntitlementSource.DEMO)).isFalse();
	}

	@Test
	void productionCannotBeHiddenBehindAnAdditionalDevProfile() {
		var properties = new LiveKitProperties();
		properties.setDemoImmediateAccessEnabled(true);
		var environment = new MockEnvironment();
		environment.setActiveProfiles("dev", "prod");
		var policy = new DemoVideoPolicy(properties, environment, mock(JdbcClient.class), Clock.systemUTC());
		assertThatThrownBy(policy::validateEnvironment).isInstanceOf(IllegalStateException.class);
	}
}

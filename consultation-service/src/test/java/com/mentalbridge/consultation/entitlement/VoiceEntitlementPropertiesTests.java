package com.mentalbridge.consultation.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

class VoiceEntitlementPropertiesTests {

	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfiguration.class);

	@Test
	void optionalUnsetAndEmptyCredentialAllowStartupButDisableHttpAccess() throws Exception {
		context.run(application -> {
			assertThat(application).hasNotFailed();
			assertThat(application.getBean(VoiceEntitlementProperties.class).authenticates("x".repeat(32))).isFalse();
		});
		context.withPropertyValues("mentalbridge.consultation.voice.journal-service-token=").run(application -> {
			assertThat(application).hasNotFailed();
			assertThat(application.getBean(VoiceEntitlementProperties.class).authenticates("")).isFalse();
		});
		for (var token : new String[] { null, "" }) {
			var request = new MockHttpServletRequest("POST", VoiceEntitlementAuthenticationFilter.PATH);
			request.addHeader("X-MentalBridge-Voice-Entitlement-Token", "x".repeat(32));
			var response = new MockHttpServletResponse();
			var chain = new MockFilterChain();
			new VoiceEntitlementAuthenticationFilter(new VoiceEntitlementProperties(token))
					.doFilter(request, response, chain);
			assertThat(response.getStatus()).isEqualTo(401);
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			var problem = new ObjectMapper().readTree(response.getContentAsString());
			assertThat(problem.size()).isEqualTo(5);
			assertThat(problem.path("code").asText()).isEqualTo("UNAUTHENTICATED");
			assertThat(chain.getRequest()).isNull();
		}
	}

	@Test
	void printableBoundaryCredentialsBindAndRequireExactMatches() {
		for (var token : new String[] { "!".repeat(32), "~".repeat(256) }) {
			context.withPropertyValues("mentalbridge.consultation.voice.journal-service-token=" + token)
					.run(application -> {
						assertThat(application).hasNotFailed();
						var properties = application.getBean(VoiceEntitlementProperties.class);
						assertThat(properties.authenticates(token)).isTrue();
						assertThat(properties.authenticates(token + "x")).isFalse();
						assertThat(properties.toString()).doesNotContain(token);
					});
		}
	}

	@Test
	void invalidConfiguredCredentialsFailBinding() {
		for (var token : new String[] { "x".repeat(31), "x".repeat(257), " ", "x".repeat(32) + " ",
				"x".repeat(32) + "\n", "x".repeat(32) + "\t", "x".repeat(32) + "\u007f", "x".repeat(32) + "é" }) {
			assertThatIllegalArgumentException().isThrownBy(() -> new VoiceEntitlementProperties(token))
					.withMessage("Voice Journal service token must be 32-256 printable ASCII characters without whitespace");
		}
		context.withPropertyValues("mentalbridge.consultation.voice.journal-service-token=short")
				.run(application -> assertThat(application).hasFailed());
	}

	@Test
	void configuredOtherPurposeCredentialCannotBeReused() {
		var token = "x".repeat(32);
		var properties = new VoiceEntitlementProperties(token);
		properties.requireDistinctFrom(null, "", "y".repeat(32));
		for (var otherCredentials : new String[][] { { token, "y".repeat(32) }, { "y".repeat(32), token } }) {
			assertThatIllegalArgumentException().isThrownBy(() -> properties.requireDistinctFrom(otherCredentials))
					.withMessage("Voice Journal service token must be purpose-specific");
		}
		new VoiceEntitlementProperties("").requireDistinctFrom("");
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(VoiceEntitlementProperties.class)
	static class PropertiesConfiguration { }
}

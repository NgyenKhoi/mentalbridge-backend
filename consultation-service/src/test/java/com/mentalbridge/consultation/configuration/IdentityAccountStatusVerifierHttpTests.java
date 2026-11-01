package com.mentalbridge.consultation.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Retryer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.mentalbridge.consultation.shared.SecurityProblemSupport;

class IdentityAccountStatusVerifierHttpTests {
	private static final String TOKEN = "synthetic-account-status-token-at-least-32-characters";
	private final AtomicInteger calls = new AtomicInteger();
	private final ObjectMapper mapper = new ObjectMapper();
	private HttpServer server;
	private volatile String responseBody = "{}";
	private volatile long responseDelayMillis;

	@BeforeEach
	void startServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/internal/v1/accounts/", exchange -> {
			calls.incrementAndGet();
			try {
				if (responseDelayMillis > 0) Thread.sleep(responseDelayMillis);
				byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				try (var output = exchange.getResponseBody()) { output.write(body); }
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			finally { exchange.close(); }
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		SecurityContextHolder.clearContext();
		server.stop(0);
	}

	@Test
	void jsonNullResponseOpensBreaker() throws Exception {
		assertMalformedResponseOpensBreaker("null");
	}

	@Test
	void nullActiveResponseOpensBreaker() throws Exception {
		assertMalformedResponseOpensBreaker("{\"active\":null}");
	}

	@Test
	void activeAndInactiveResponsesRemainDistinct() {
		var verifier = verifier(Duration.ofMillis(250), Duration.ofMillis(750));
		responseBody = "{\"active\":true}";
		assertThat(verifier.isActive(UUID.randomUUID(), UUID.randomUUID())).isTrue();
		responseBody = "{\"active\":false}";
		assertThat(verifier.isActive(UUID.randomUUID(), UUID.randomUUID())).isFalse();
		assertThat(calls.get()).isEqualTo(2);
	}

	@Test
	void finiteReadDeadlineRejectsSlowIdentity() throws Exception {
		responseDelayMillis = 750;
		var properties = properties(Duration.ofMillis(250), Duration.ofMillis(100));
		var options = new IdentityAccountStatusHttpClient.Configuration().identityAccountStatusDeadlines(properties);
		assertThat(options.connectTimeoutMillis()).isEqualTo(250);
		assertThat(options.readTimeoutMillis()).isEqualTo(100);
		var verifier = verifier(properties);
		var jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject(UUID.randomUUID().toString())
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		var response = new MockHttpServletResponse();
		var filter = new AccountStatusEnforcementFilter(verifier, new SecurityProblemSupport(mapper));
		filter.doFilterInternal(new MockHttpServletRequest("GET", "/protected"), response, new MockFilterChain());
		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(response.getContentAsString()).contains("authentication_dependency_unavailable");
	}

	@Test
	void invalidTimeoutsFailContextStartup() {
		var runner = new ApplicationContextRunner().withUserConfiguration(TimeoutPropertiesConfiguration.class);
		for (String invalid : List.of("PT0S", "PT-1S", "PT0.0001S")) {
			runner.withPropertyValues("mentalbridge.consultation.identity-account-status.connect-timeout=" + invalid,
					"mentalbridge.consultation.identity-account-status.read-timeout=PT0.75S")
					.run(context -> assertThat(context).hasFailed());
			runner.withPropertyValues("mentalbridge.consultation.identity-account-status.connect-timeout=PT0.25S",
					"mentalbridge.consultation.identity-account-status.read-timeout=" + invalid)
					.run(context -> assertThat(context).hasFailed());
		}
		runner.withPropertyValues("mentalbridge.consultation.identity-account-status.connect-timeout=PT0.251S",
				"mentalbridge.consultation.identity-account-status.read-timeout=PT0.75S")
				.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("mentalbridge.consultation.identity-account-status.connect-timeout=PT0.250000001S",
				"mentalbridge.consultation.identity-account-status.read-timeout=PT0.75S")
				.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("mentalbridge.consultation.identity-account-status.connect-timeout=PT0.25S",
				"mentalbridge.consultation.identity-account-status.read-timeout=PT0.751S")
				.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("mentalbridge.consultation.identity-account-status.connect-timeout=PT0.25S",
				"mentalbridge.consultation.identity-account-status.read-timeout=PT0.750000001S")
				.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("mentalbridge.consultation.identity-account-status.connect-timeout=PT999999999999S",
				"mentalbridge.consultation.identity-account-status.read-timeout=PT0.75S")
				.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("mentalbridge.consultation.identity-account-status.read-timeout=PT0.75S")
				.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("mentalbridge.consultation.identity-account-status.connect-timeout=PT0.25S")
				.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("mentalbridge.consultation.identity-account-status.connect-timeout=PT0.001S",
				"mentalbridge.consultation.identity-account-status.read-timeout=PT0.001S")
				.run(context -> {
					assertThat(context).hasNotFailed();
					var options = new IdentityAccountStatusHttpClient.Configuration()
							.identityAccountStatusDeadlines(context.getBean(IdentityAccountStatusProperties.class));
					assertThat(options.connectTimeoutMillis()).isEqualTo(1);
					assertThat(options.readTimeoutMillis()).isEqualTo(1);
				});
		runner.withPropertyValues("mentalbridge.consultation.identity-account-status.connect-timeout=PT0.25S",
				"mentalbridge.consultation.identity-account-status.read-timeout=PT0.75S")
				.run(context -> {
					assertThat(context).hasNotFailed();
					var options = new IdentityAccountStatusHttpClient.Configuration()
							.identityAccountStatusDeadlines(context.getBean(IdentityAccountStatusProperties.class));
					assertThat(options.connectTimeoutMillis()).isEqualTo(250);
					assertThat(options.readTimeoutMillis()).isEqualTo(750);
				});
	}

	private void assertMalformedResponseOpensBreaker(String body) throws Exception {
		responseBody = body;
		var verifier = verifier(Duration.ofMillis(250), Duration.ofMillis(750));
		var accountId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		for (int attempt = 0; attempt < 5; attempt++) {
			assertThatThrownBy(() -> verifier.isActive(accountId, correlationId))
					.isInstanceOf(IdentityAccountStatusVerifier.IdentityAccountStatusUnavailableException.class);
		}
		assertThat(calls.get()).isEqualTo(5);

		var jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject(accountId.toString())
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		var response = new MockHttpServletResponse();
		var filter = new AccountStatusEnforcementFilter(verifier, new SecurityProblemSupport(mapper));
		filter.doFilterInternal(new MockHttpServletRequest("GET", "/protected"), response, new MockFilterChain());
		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(response.getContentAsString()).contains("authentication_dependency_unavailable");
		assertThat(calls.get()).isEqualTo(5);
	}

	private IdentityAccountStatusProperties properties(Duration connect, Duration read) {
		return new IdentityAccountStatusProperties("http://127.0.0.1:" + server.getAddress().getPort(),
				TOKEN, connect, read);
	}

	private IdentityAccountStatusVerifier verifier(Duration connect, Duration read) {
		return verifier(properties(connect, read));
	}

	private IdentityAccountStatusVerifier verifier(IdentityAccountStatusProperties properties) {
		var options = new IdentityAccountStatusHttpClient.Configuration().identityAccountStatusDeadlines(properties);
		var client = Feign.builder().contract(new SpringMvcContract()).retryer(Retryer.NEVER_RETRY)
				.options(options)
				.decoder((response, type) -> response.body() == null ? null
						: mapper.readValue(response.body().asInputStream(), IdentityAccountStatusHttpClient.Result.class))
				.target(IdentityAccountStatusHttpClient.class, properties.baseUrl());
		return new IdentityAccountStatusVerifier(client, properties);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(IdentityAccountStatusProperties.class)
	static class TimeoutPropertiesConfiguration {
		@Bean(name = "configurationPropertiesValidator")
		static LocalValidatorFactoryBean configurationPropertiesValidator() {
			return new LocalValidatorFactoryBean();
		}
	}
}

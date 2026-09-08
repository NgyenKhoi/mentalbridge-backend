package com.mentalbridge.identity.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.IdentityTestProperties;
import com.mentalbridge.identity.TestcontainersConfiguration;
import com.mentalbridge.identity.registration.RegistrationRequested;

@Import({ TestcontainersConfiguration.class, IdentitySessionFlowIntegrationTests.CaptureConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
class IdentitySessionFlowIntegrationTests extends IdentityTestProperties {

	private static final Set<String> IMPLEMENTED_OPERATIONS = Set.of(
			"POST /api/v1/auth/registrations",
			"POST /api/v1/auth/email-verifications",
			"POST /api/v1/auth/login",
			"POST /api/v1/auth/refresh",
			"POST /api/v1/auth/logout",
			"POST /api/v1/auth/logout-all",
			"GET /api/v1/account");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private VerificationCapture verificationCapture;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Test
	void healthProbeIsPublic() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void registrationVerificationLoginRefreshReplayAndLogoutFormOneSecureFlow() throws Exception {
		var registrationBody = """
				{"email":"flow@example.com","password":"correct-horse-battery-staple","actorType":"USER"}
				""";
		var firstRegistration = mvc.perform(post("/api/v1/auth/registrations")
				.header("Idempotency-Key", "registration-flow-0001").contentType(MediaType.APPLICATION_JSON)
				.content(registrationBody)).andExpect(status().isCreated()).andReturn();
		var firstAccountId = json(firstRegistration.getResponse().getContentAsString()).get("accountId").asText();

		mvc.perform(post("/api/v1/auth/registrations").header("Idempotency-Key", "registration-flow-0001")
				.contentType(MediaType.APPLICATION_JSON).content(registrationBody)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.accountId").value(firstAccountId));

		var challenge = verificationCapture.challenge();
		assertThat(challenge).isNotBlank();
		mvc.perform(post("/api/v1/auth/email-verifications").contentType(MediaType.APPLICATION_JSON)
				.content("{\"challenge\":\"" + challenge + "\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

		var login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"email":"FLOW@example.com","password":"correct-horse-battery-staple","deviceLabel":"integration-test"}
						""")).andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andReturn();
		var loginTokens = json(login.getResponse().getContentAsString());

		mvc.perform(get("/api/v1/account").header("Authorization", "Bearer " + loginTokens.get("accessToken").asText()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(firstAccountId))
				.andExpect(jsonPath("$.roles[0]").value("USER"));

		var refreshBody = "{\"refreshToken\":\"" + loginTokens.get("refreshToken").asText() + "\"}";
		var firstRefresh = mvc.perform(post("/api/v1/auth/refresh").header("Idempotency-Key", "refresh-flow-000001")
				.contentType(MediaType.APPLICATION_JSON).content(refreshBody)).andExpect(status().isOk()).andReturn();
		var rotatedTokens = json(firstRefresh.getResponse().getContentAsString());

		mvc.perform(post("/api/v1/auth/refresh").header("Idempotency-Key", "refresh-flow-000001")
				.contentType(MediaType.APPLICATION_JSON).content(refreshBody)).andExpect(status().isOk())
				.andExpect(jsonPath("$.refreshToken").value(rotatedTokens.get("refreshToken").asText()));

		mvc.perform(post("/api/v1/auth/logout")
				.header("Authorization", "Bearer " + rotatedTokens.get("accessToken").asText())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + rotatedTokens.get("refreshToken").asText() + "\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(post("/api/v1/auth/refresh").header("Idempotency-Key", "refresh-after-logout")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + rotatedTokens.get("refreshToken").asText() + "\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SESSION"));
	}

	@Test
	void refreshReplayRevokesTheRotatedSessionFamily() throws Exception {
		var tokens = registerVerifyAndLogin("replay@example.com", "registration-replay-01");
		var originalRefreshBody = "{\"refreshToken\":\"" + tokens.get("refreshToken").asText() + "\"}";
		var rotation = mvc.perform(post("/api/v1/auth/refresh").header("Idempotency-Key", "refresh-replay-good")
				.contentType(MediaType.APPLICATION_JSON).content(originalRefreshBody)).andExpect(status().isOk())
				.andReturn();
		var successor = json(rotation.getResponse().getContentAsString());

		mvc.perform(post("/api/v1/auth/refresh").header("Idempotency-Key", "refresh-replay-attack")
				.contentType(MediaType.APPLICATION_JSON).content(originalRefreshBody)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_SESSION"));

		mvc.perform(post("/api/v1/auth/refresh").header("Idempotency-Key", "refresh-replay-successor")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + successor.get("refreshToken").asText() + "\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SESSION"));
	}

	@Test
	void authenticationAndAuthorizationFailuresArePrivacySafe() throws Exception {
		mvc.perform(post("/api/v1/auth/registrations").header("Idempotency-Key", "registration-unverified")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"unverified@example.com","password":"correct-horse-battery-staple","actorType":"USER"}
						""")).andExpect(status().isCreated());

		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"unverified@example.com","password":"correct-horse-battery-staple"}
				""")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		var unknownAccountFailure = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"unknown@example.com","password":"correct-horse-battery-staple"}
				"""))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").isNotEmpty())
				.andExpect(jsonPath("$.title").isNotEmpty())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
				.andExpect(jsonPath("$.correlationId").isNotEmpty())
				.andReturn();
		assertThat(unknownAccountFailure.getResponse().getContentAsString())
				.doesNotContain("unknown@example.com", "correct-horse-battery-staple");
		mvc.perform(get("/api/v1/account")).andExpect(status().isUnauthorized());

		var tokens = registerVerifyAndLogin("ordinary-user@example.com", "registration-authz-01");
		mvc.perform(get("/api/v1/admin/accounts").header("Authorization", "Bearer " + tokens.get("accessToken").asText()))
				.andExpect(status().isForbidden());
	}

	@Test
	void idempotencyKeyCannotBeReusedForDifferentRegistrationInput() throws Exception {
		var key = "registration-conflict-key";
		mvc.perform(post("/api/v1/auth/registrations").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"first@example.com","password":"correct-horse-battery-staple","actorType":"USER"}
						""")).andExpect(status().isCreated());
		mvc.perform(post("/api/v1/auth/registrations").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"second@example.com","password":"correct-horse-battery-staple","actorType":"USER"}
						""")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void publicRegistrationRejectsTheDedicatedAdministratorRole() throws Exception {
		var initialOutboxCount = jdbc.sql("select count(*) from outbox_event").query(Long.class).single();
		mvc.perform(post("/api/v1/auth/registrations").header("Idempotency-Key", "registration-admin-denied")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"admin-candidate@example.com","password":"correct-horse-battery-staple","actorType":"ADMIN"}
						"""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(post("/api/v1/auth/registrations").header("Idempotency-Key", "registration-owner-denied")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"owner-candidate@example.com","password":"correct-horse-battery-staple","actorType":"OWNER"}
						"""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		var accountCount = jdbc.sql("""
				select count(*) from account
				where email in ('admin-candidate@example.com', 'owner-candidate@example.com')
				""")
				.query(Long.class).single();
		assertThat(accountCount).isZero();
		assertThat(jdbc.sql("select count(*) from outbox_event").query(Long.class).single())
				.isEqualTo(initialOutboxCount);
	}

	@Test
	void repeatedCredentialFailuresLockTheAccountWithoutChangingThePublicError() throws Exception {
		var email = "locked-user@example.com";
		registerAndVerify(email, "registration-lockout-01");

		for (var attempt = 0; attempt < 5; attempt++) {
			mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"" + email + "\",\"password\":\"definitely-wrong-password\"}"))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		}

		var lockState = jdbc.sql("""
				select failed_login_count, locked_until > now() as locked
				from account where email = :email
				""").param("email", email).query((row, metadata) -> List.of(row.getInt(1), row.getBoolean(2))).single();
		assertThat(lockState).containsExactly(5, true);
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email
						+ "\",\"password\":\"correct-horse-battery-staple\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void inactiveAccountCannotLoginOrRefresh() throws Exception {
		var email = "disabled-user@example.com";
		var tokens = registerVerifyAndLogin(email, "registration-disabled-01");
		jdbc.sql("update account set status = 'DISABLED' where email = :email").param("email", email).update();

		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email
						+ "\",\"password\":\"correct-horse-battery-staple\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		mvc.perform(post("/api/v1/auth/refresh").header("Idempotency-Key", "refresh-disabled-account")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + tokens.get("refreshToken").asText() + "\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_SESSION"));
	}

	@Test
	void concurrentRefreshUseAllowsOnlyOneRotationAndRevokesItsFamily() throws Exception {
		var tokens = registerVerifyAndLogin("concurrent-refresh@example.com", "registration-concurrent-01");
		var body = "{\"refreshToken\":\"" + tokens.get("refreshToken").asText() + "\"}";
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> refreshAfterSignal(body, "refresh-concurrent-first", ready, start));
			var second = executor.submit(() -> refreshAfterSignal(body, "refresh-concurrent-second", ready, start));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			var results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

			assertThat(results).extracting(result -> result.getResponse().getStatus())
					.containsExactlyInAnyOrder(200, 401);
			var successfulRotation = results.stream()
					.filter(result -> result.getResponse().getStatus() == 200)
					.findFirst().orElseThrow();
			var successor = json(successfulRotation.getResponse().getContentAsString());
			mvc.perform(post("/api/v1/auth/refresh").header("Idempotency-Key", "refresh-concurrent-successor")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"refreshToken\":\"" + successor.get("refreshToken").asText() + "\"}"))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("INVALID_SESSION"));
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void logoutAllIsIdempotentAndRevokesExistingRefreshSessions() throws Exception {
		var tokens = registerVerifyAndLogin("logout-all@example.com", "registration-logout-all-01");
		var authorization = "Bearer " + tokens.get("accessToken").asText();

		mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", authorization))
				.andExpect(status().isNoContent());
		mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", authorization))
				.andExpect(status().isNoContent());
		mvc.perform(post("/api/v1/auth/refresh").header("Idempotency-Key", "refresh-after-logout-all")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + tokens.get("refreshToken").asText() + "\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_SESSION"));
	}

	@Test
	void runtimeHandlersExactlyMatchTheImplementedOpenApiOperations() {
		var operations = handlerMapping.getHandlerMethods().keySet().stream()
				.flatMap(mapping -> mapping.getPatternValues().stream()
						.flatMap(path -> mapping.getMethodsCondition().getMethods().stream()
								.map(method -> method.name() + " " + path)))
				.filter(operation -> operation.contains(" /api/v1/"))
				.collect(java.util.stream.Collectors.toSet());

		assertThat(operations).isEqualTo(IMPLEMENTED_OPERATIONS);
	}

	private JsonNode registerVerifyAndLogin(String email, String idempotencyKey) throws Exception {
		registerAndVerify(email, idempotencyKey);
		var login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email
						+ "\",\"password\":\"correct-horse-battery-staple\"}"))
				.andExpect(status().isOk()).andReturn();
		return json(login.getResponse().getContentAsString());
	}

	private void registerAndVerify(String email, String idempotencyKey) throws Exception {
		mvc.perform(post("/api/v1/auth/registrations").header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email
						+ "\",\"password\":\"correct-horse-battery-staple\",\"actorType\":\"USER\"}"))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/v1/auth/email-verifications").contentType(MediaType.APPLICATION_JSON)
				.content("{\"challenge\":\"" + verificationCapture.challenge() + "\"}"))
				.andExpect(status().isOk());
	}

	private MvcResult refreshAfterSignal(String body, String idempotencyKey, CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Concurrent refresh start signal timed out");
		}
		return mvc.perform(post("/api/v1/auth/refresh").header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
	}

	private JsonNode json(String value) throws Exception {
		return objectMapper.readTree(value);
	}

	static class VerificationCapture {
		private final AtomicReference<String> challenge = new AtomicReference<>();

		@EventListener
		void capture(RegistrationRequested requested) {
			challenge.set(requested.challenge());
		}

		String challenge() {
			return challenge.get();
		}
	}

	static class CaptureConfiguration {
		@Bean
		VerificationCapture verificationCapture() {
			return new VerificationCapture();
		}
	}

}

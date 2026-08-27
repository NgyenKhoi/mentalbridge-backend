package com.mentalbridge.identity.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.IdentityTestProperties;
import com.mentalbridge.identity.TestcontainersConfiguration;
import com.mentalbridge.identity.registration.RegistrationRequested;

@Import({ TestcontainersConfiguration.class, IdentitySessionFlowIntegrationTests.CaptureConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
class IdentitySessionFlowIntegrationTests extends IdentityTestProperties {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private VerificationCapture verificationCapture;

	@Autowired
	private JdbcClient jdbc;

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
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"unknown@example.com","password":"correct-horse-battery-staple"}
				""")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
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
		mvc.perform(post("/api/v1/auth/registrations").header("Idempotency-Key", "registration-admin-denied")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"admin-candidate@example.com","password":"correct-horse-battery-staple","actorType":"ADMIN"}
						"""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		var accountCount = jdbc.sql("select count(*) from account where email = 'admin-candidate@example.com'")
				.query(Long.class).single();
		assertThat(accountCount).isZero();
	}

	private JsonNode registerVerifyAndLogin(String email, String idempotencyKey) throws Exception {
		mvc.perform(post("/api/v1/auth/registrations").header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email
						+ "\",\"password\":\"correct-horse-battery-staple\",\"actorType\":\"USER\"}"))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/v1/auth/email-verifications").contentType(MediaType.APPLICATION_JSON)
				.content("{\"challenge\":\"" + verificationCapture.challenge() + "\"}"))
				.andExpect(status().isOk());
		var login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email
						+ "\",\"password\":\"correct-horse-battery-staple\"}"))
				.andExpect(status().isOk()).andReturn();
		return json(login.getResponse().getContentAsString());
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

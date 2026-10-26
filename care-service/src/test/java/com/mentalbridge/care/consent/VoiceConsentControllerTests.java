package com.mentalbridge.care.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.configuration.CareJwtProperties;
import com.mentalbridge.care.configuration.IdentityAccountStatusVerifier;
import com.mentalbridge.care.configuration.SecurityConfiguration;
import com.mentalbridge.care.configuration.VoiceConsentSecurityConfiguration;
import com.mentalbridge.care.shared.SecurityProblemSupport;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;

@WebMvcTest(controllers = VoiceConsentController.class, properties = "springdotenv.enabled=false")
@ContextConfiguration(classes = { VoiceConsentController.class, VoiceConsentExceptionHandler.class,
		VoiceConsentSecurityConfiguration.class, SecurityConfiguration.class, SecurityProblemSupport.class,
		VoiceProblemSupport.class, VoiceConsentControllerTests.Configuration.class })
class VoiceConsentControllerTests extends CareTestProperties {
	private static final String PATH = "/api/v1/consents/ai-voice-processing";
	private static final String INTERNAL = VoiceConsentSecurityConfiguration.AUTHORIZATION_PATH;
	private static final String TOKEN = "synthetic-journal-to-care-token-at-least-32-characters";
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper mapper;
	@MockitoBean VoiceConsentService service;
	@MockitoBean IdentityAccountStatusVerifier accountStatus;

	@BeforeEach
	void activeAccounts() {
		when(accountStatus.isActive(any(), any())).thenReturn(true);
	}

	@Test
	void publicVoiceEndpointsRequireUserAndReturnClosedNoStoreSecurityProblems() throws Exception {
		for (var path : java.util.List.of(PATH, "/api/v1/ai-voice-processing-disclosure")) {
			var missing = mvc.perform(get(path)).andExpect(status().isUnauthorized())
					.andExpect(header().string("Cache-Control", "no-store")).andReturn();
			assertProblem(missing.getResponse().getContentAsString(), "UNAUTHENTICATED");
			for (var role : java.util.List.of("ROLE_SPECIALIST", "ROLE_ADMIN")) {
				var forbidden = mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority(role))))
						.andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
				assertProblem(forbidden.getResponse().getContentAsString(), "FORBIDDEN");
			}
		}
		verifyNoInteractions(service);
	}

	@Test
	void serviceOnlyReadRequiresItsOwnCredentialAndExactPurposeBinding() throws Exception {
		var owner = UUID.randomUUID();
		var session = UUID.randomUUID();
		var body = "{\"ownerId\":\"" + owner + "\",\"voiceSessionId\":\"" + session + "\",\"purpose\":\"AI_COMPANION_VOICE\"}";
		for (var candidate : java.util.List.of("", "synthetic-worker-token-at-least-32-characters")) {
			mvc.perform(post(INTERNAL).header("X-MentalBridge-Voice-Care-Token", candidate)
					.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized())
					.andExpect(header().string("Cache-Control", "no-store"));
		}
		mvc.perform(post(INTERNAL).with(user(owner)).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isForbidden());
		when(service.authorize(owner, session)).thenReturn(new VoiceConsentService.Authorization(owner, session,
				"AI_COMPANION_VOICE", true, true, VoiceConsentService.VERSION, true, Instant.now()));
		mvc.perform(post(INTERNAL).header("X-MentalBridge-Voice-Care-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.ownerId").value(owner.toString()))
				.andExpect(jsonPath("$.voiceSessionId").value(session.toString()));
		for (var invalid : java.util.List.of(body.replace("AI_COMPANION_VOICE", "TEXT_CHAT"),
				body.replace(owner.toString(), "1-1-1-1-1"), body.replace("}", ",\"userId\":\"injected\"}"),
				body + " {}", body.replace("}", ",\"ownerId\":\"" + UUID.randomUUID() + "\"}"))) {
			var result = mvc.perform(post(INTERNAL).header("X-MentalBridge-Voice-Care-Token", TOKEN)
					.contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isUnprocessableEntity()).andReturn();
			assertProblem(result.getResponse().getContentAsString(), "INVALID_REQUEST");
		}
		verify(service).authorize(owner, session);
	}

	@Test
	void validatesVoiceCommandTypesUnknownFieldsVersionAndUuidIdempotency() throws Exception {
		var owner = UUID.randomUUID();
		var valid = "{\"consentVersion\":\"ai-voice-processing-capstone-v1\",\"granted\":true}";
		for (var invalid : java.util.List.of("{}", valid.replace("true", "\"true\""),
				valid.replace("true", "null"), valid.replace("capstone-v1", "capstone-v0"),
				valid.replace("}", ",\"ownerId\":\"injected\"}"), valid + " {}",
				valid.replace("}", ",\"granted\":false}"), valid.replace("true", "1"))) {
			var result = mvc.perform(post(PATH).with(user(owner)).header("Idempotency-Key", UUID.randomUUID())
					.contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isUnprocessableEntity())
					.andExpect(header().string("Cache-Control", "no-store")).andReturn();
			assertProblem(result.getResponse().getContentAsString(), "INVALID_REQUEST");
		}
		mvc.perform(post(PATH).with(user(owner)).header("Idempotency-Key", "1-1-1-1-1")
				.contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isUnprocessableEntity());
		mvc.perform(post(PATH).with(user(owner)).contentType(MediaType.APPLICATION_JSON).content(valid))
				.andExpect(status().isUnprocessableEntity());
		verifyNoInteractions(service);
	}

	@Test
	void unsupportedMethodsAndMediaTypesRemainClosedVoiceProblems() throws Exception {
		var owner = UUID.randomUUID();
		for (var method : java.util.List.of("PUT", "DELETE", "PATCH", "OPTIONS")) {
			var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
					org.springframework.http.HttpMethod.valueOf(method), PATH).with(user(owner)))
					.andExpect(status().isUnprocessableEntity()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
			assertProblem(result.getResponse().getContentAsString(), "INVALID_REQUEST");
		}
		for (var type : java.util.List.of("text/plain", "application/xml", "application/problem+json")) {
			var result = mvc.perform(post(PATH).with(user(owner)).contentType(type).content("{}"))
					.andExpect(status().isUnprocessableEntity()).andReturn();
			assertProblem(result.getResponse().getContentAsString(), "INVALID_REQUEST");
		}
		mvc.perform(post(PATH).with(user(owner)).content("{}")).andExpect(status().isUnprocessableEntity());
		mvc.perform(get(INTERNAL).header("X-MentalBridge-Voice-Care-Token", TOKEN)).andExpect(status().isUnprocessableEntity());
		mvc.perform(post("/api/v1/ai-voice-processing-disclosure").with(user(owner)).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnprocessableEntity());
		verifyNoInteractions(service);
	}

	@Test
	void propagatesOnlyVerifiedOwnerAndMapsDependencyFailureWithoutDetails() throws Exception {
		var owner = UUID.randomUUID();
		when(service.current(owner)).thenReturn(new VoiceConsentService.Consent("AI_VOICE_PROCESSING",
				VoiceConsentService.VERSION, false, false, null, null));
		mvc.perform(get(PATH).with(user(owner))).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.acceptedAt").doesNotExist()).andExpect(jsonPath("$.revokedAt").doesNotExist());
		when(service.authorize(any(), any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private db details"));
		var result = mvc.perform(post(INTERNAL).header("X-MentalBridge-Voice-Care-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":\"" + owner
						+ "\",\"voiceSessionId\":\"" + UUID.randomUUID() + "\",\"purpose\":\"AI_COMPANION_VOICE\"}"))
				.andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
		assertProblem(result.getResponse().getContentAsString(), "CONSENT_UNAVAILABLE");
		assertThat(result.getResponse().getContentAsString()).doesNotContain("private db");
	}

	private void assertProblem(String body, String code) throws Exception {
		var problem = mapper.readTree(body);
		assertThat(problem.properties()).extracting(java.util.Map.Entry::getKey)
				.containsExactlyInAnyOrder("type", "title", "status", "code", "correlationId");
		assertThat(problem.path("code").asText()).isEqualTo(code);
		assertThat(java.net.URI.create(problem.path("type").asText()).isAbsolute()).isTrue();
		assertThat(UUID.fromString(problem.path("correlationId").asText())).isNotNull();
		var proposal = mapper.readTree(java.nio.file.Path.of("..", "contracts", "proposals", "companion-voice-v1.openapi.json").toFile());
		var validator = SchemaRegistry.withDialect(Dialects.getDraft202012())
				.getSchema(proposal.at("/components/schemas/Problem").toString(), InputFormat.JSON);
		assertThat(validator.validate(problem, context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))).isEmpty();
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID owner) {
		return jwt().jwt(token -> token.subject(owner.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties({ CareJwtProperties.class,
			com.mentalbridge.care.configuration.SupportPlanCompanionClientProperties.class })
	static class Configuration {
		@Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
		@Bean VoiceConsentProperties voiceConsentProperties() {
			return new VoiceConsentProperties(TOKEN, "synthetic-hmac-at-least-32-characters");
		}
	}
}

package com.mentalbridge.consultation.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.configuration.ConsultationJwtProperties;
import com.mentalbridge.consultation.configuration.IdentityAccountStatusVerifier;
import com.mentalbridge.consultation.configuration.SecurityConfiguration;
import com.mentalbridge.consultation.shared.SecurityProblemSupport;

@WebMvcTest(controllers = VoiceEntitlementController.class, properties = {
		"mentalbridge.consultation.voice.journal-service-token=synthetic-voice-journal-token-32-characters" })
@Import({ SecurityConfiguration.class, SecurityProblemSupport.class, CurrentServiceEntitlementService.class,
		VoiceEntitlementExceptionHandler.class })
@EnableConfigurationProperties({ ConsultationJwtProperties.class, VoiceEntitlementProperties.class })
class VoiceEntitlementControllerTests extends ConsultationTestProperties {

	private static final String TOKEN = "synthetic-voice-journal-token-32-characters";
	private static final String HEADER = "X-MentalBridge-Voice-Entitlement-Token";
	private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
	private static final UUID SESSION = UUID.fromString("22222222-2222-4222-8222-222222222222");
	private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
	private static final String BODY = """
			{"ownerId":"%s","voiceSessionId":"%s","purpose":"AI_COMPANION_VOICE"}
			""".formatted(OWNER, SESSION);

	@Autowired MockMvc mvc;
	@MockitoBean CurrentServiceEntitlementRepository repository;
	@MockitoBean Clock clock;
	@MockitoBean IdentityAccountStatusVerifier accountStatus;

	@BeforeEach
	void setup() {
		reset(repository, clock);
		when(clock.instant()).thenReturn(NOW);
		when(accountStatus.isActive(any(), any())).thenReturn(true);
	}

	@Test
	void unknownOwnerReturnsOnlyDefaultFreeAndEchoesTheTrustedRequestBinding() throws Exception {
		when(repository.findById(OWNER)).thenReturn(Optional.empty());
		var response = mvc.perform(command(BODY)).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.ownerId").value(OWNER.toString()))
				.andExpect(jsonPath("$.voiceSessionId").value(SESSION.toString()))
				.andExpect(jsonPath("$.purpose").value("AI_COMPANION_VOICE"))
				.andExpect(jsonPath("$.packageCode").value("FREE"))
				.andExpect(jsonPath("$.source").value("DEFAULT_FREE"))
				.andExpect(jsonPath("$.entitlementVersion").value("service-entitlement-v1:DEFAULT_FREE:0"))
				.andExpect(jsonPath("$.checkedAt").value(NOW.toString()))
				.andExpect(jsonPath("$.validUntil").doesNotExist()).andReturn();
		assertThat(new ObjectMapper().readTree(response.getResponse().getContentAsString()).size()).isEqualTo(7);
		verify(repository).findById(OWNER);
		verifyNoMoreInteractions(repository);
	}

	@Test
	void paidAndDemoUseOnlyCurrentOwnerFactsWithoutFinancialMutations() throws Exception {
		for (var source : new EntitlementSource[] { EntitlementSource.PAID, EntitlementSource.DEMO }) {
			reset(repository);
			var current = grant(ServicePackage.PREMIUM, source, NOW.minusSeconds(1), NOW.plusSeconds(60));
			when(repository.findById(OWNER)).thenReturn(Optional.of(current));
			mvc.perform(command(BODY)).andExpect(status().isOk())
					.andExpect(jsonPath("$.packageCode").value("PREMIUM"))
					.andExpect(jsonPath("$.source").value(source.name()))
					.andExpect(jsonPath("$.entitlementVersion").value("service-entitlement-v1:" + source + ":7"))
					.andExpect(jsonPath("$.validUntil").value(NOW.plusSeconds(60).toString()));
			verify(repository).findById(OWNER);
			verifyNoMoreInteractions(repository);
		}
	}

	@Test
	void expiredBoundaryAndFutureGrantsNormalizeToFree() throws Exception {
		for (var interval : new Instant[][] { { NOW.minusSeconds(60), NOW.minusSeconds(1) },
				{ NOW.minusSeconds(60), NOW }, { NOW.plusSeconds(1), NOW.plusSeconds(60) } }) {
			var current = grant(ServicePackage.PLUS, EntitlementSource.DEMO, interval[0], interval[1]);
			when(repository.findById(OWNER)).thenReturn(Optional.of(current));
			mvc.perform(command(BODY)).andExpect(status().isOk())
					.andExpect(jsonPath("$.packageCode").value("FREE"))
					.andExpect(jsonPath("$.source").value("DEFAULT_FREE"))
					.andExpect(jsonPath("$.validUntil").doesNotExist());
		}
	}

	@Test
	void grantExpiringDuringTheReadFailsClosedRatherThanReturningExpiredPaidAccess() throws Exception {
		var current = grant(ServicePackage.PLUS, EntitlementSource.PAID, NOW.minusSeconds(1), NOW.plusSeconds(1));
		when(repository.findById(OWNER)).thenReturn(Optional.of(current));
		when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(1));
		assertProblem(mvc.perform(command(BODY)).andExpect(status().isServiceUnavailable()).andReturn(),
				503, "ENTITLEMENT_UNAVAILABLE");
	}

	@Test
	void missingWrongWorkerAndUserCredentialsNeverReachOwnerData() throws Exception {
		for (var request : new MockHttpServletRequestBuilder[] {
				post(VoiceEntitlementAuthenticationFilter.PATH).contentType(MediaType.APPLICATION_JSON).content(BODY),
				command(BODY).header(HEADER, "second-token"),
				post(VoiceEntitlementAuthenticationFilter.PATH).header(HEADER, "wrong-token")
						.contentType(MediaType.APPLICATION_JSON).content(BODY),
				post(VoiceEntitlementAuthenticationFilter.PATH).header("X-MentalBridge-Voice-Worker-Token", TOKEN)
						.contentType(MediaType.APPLICATION_JSON).content(BODY),
				post(VoiceEntitlementAuthenticationFilter.PATH).header("Authorization", "Bearer synthetic-user")
						.contentType(MediaType.APPLICATION_JSON).content("not-json") }) {
			assertProblem(mvc.perform(request).andExpect(status().isUnauthorized()).andReturn(), 401, "UNAUTHENTICATED");
		}
		assertProblem(mvc.perform(command(BODY).header("Authorization", "Bearer synthetic-user"))
				.andExpect(status().isForbidden()).andReturn(), 403, "FORBIDDEN");
		verifyNoInteractions(repository);
	}

	@Test
	void closedRequestRejectsMissingMalformedOverpostedAndUnboundContext() throws Exception {
		for (var body : new String[] { "", "{}", "null", "[]", "not-json", BODY + "{}", BODY.replace(OWNER.toString(), "1-1-1-1-1"),
				BODY.replace("AI_COMPANION_VOICE", "OTHER"), BODY.replace("\"purpose\"", "\"subject\""),
				BODY.replace("}", ",\"packageCode\":\"PREMIUM\"}"),
				BODY.replace("}", ",\"ownerId\":\"" + UUID.randomUUID() + "\"}"),
				BODY.replace("\"" + SESSION + "\"", "null") }) {
			assertProblem(mvc.perform(command(body)).andExpect(status().isUnprocessableContent()).andReturn(),
					422, "INVALID_REQUEST");
		}
		assertProblem(mvc.perform(command("{}").contentType(MediaType.TEXT_PLAIN))
				.andExpect(status().isUnprocessableContent()).andReturn(), 422, "INVALID_REQUEST");
		assertProblem(mvc.perform(get(VoiceEntitlementAuthenticationFilter.PATH).header(HEADER, TOKEN))
				.andExpect(status().isUnprocessableContent()).andReturn(), 422, "INVALID_REQUEST");
		verifyNoInteractions(repository);
	}

	@Test
	void dependencyFailureAndInconsistentFactsFailClosedWithoutLeakingDetails() throws Exception {
		when(repository.findById(OWNER)).thenThrow(new IllegalStateException("private JDBC failure and token"));
		assertProblem(mvc.perform(command(BODY)).andExpect(status().isServiceUnavailable()).andReturn(),
				503, "ENTITLEMENT_UNAVAILABLE");
		reset(repository);
		var current = grant(ServicePackage.PLUS, EntitlementSource.DEFAULT_FREE, NOW.minusSeconds(1), NOW.plusSeconds(60));
		when(repository.findById(OWNER)).thenReturn(Optional.of(current));
		assertProblem(mvc.perform(command(BODY)).andExpect(status().isServiceUnavailable()).andReturn(),
				503, "ENTITLEMENT_UNAVAILABLE");
	}

	@Test
	void blankCredentialIsDisabledAndItsDiagnosticStringIsRedacted() {
		assertThat(new VoiceEntitlementProperties("").authenticates(TOKEN)).isFalse();
		assertThat(new VoiceEntitlementProperties(null).authenticates(TOKEN)).isFalse();
		assertThat(new VoiceEntitlementProperties(TOKEN).toString()).doesNotContain(TOKEN);
	}

	@Test
	void legacyEntitlementAuthenticationKeepsItsExistingProblemContract() throws Exception {
		mvc.perform(get("/internal/v1/entitlements/current"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value("/problems/unauthenticated"))
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		verifyNoInteractions(repository);
	}

	private CurrentServiceEntitlementEntity grant(ServicePackage plan, EntitlementSource source, Instant from,
			Instant until) {
		var grant = mock(CurrentServiceEntitlementEntity.class);
		when(grant.accountId()).thenReturn(OWNER);
		when(grant.packageCode()).thenReturn(plan);
		when(grant.source()).thenReturn(source);
		when(grant.effectiveFrom()).thenReturn(from);
		when(grant.effectiveUntil()).thenReturn(until);
		when(grant.policyVersion()).thenReturn("service-entitlement-v1");
		when(grant.version()).thenReturn(7L);
		return grant;
	}

	private MockHttpServletRequestBuilder command(String body) {
		return post(VoiceEntitlementAuthenticationFilter.PATH).header(HEADER, TOKEN)
				.header("X-Correlation-Id", SESSION).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private void assertProblem(MvcResult result, int status, String code) throws Exception {
		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		var body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
		assertThat(body.size()).isEqualTo(5);
		assertThat(java.net.URI.create(body.path("type").asText()).isAbsolute()).isTrue();
		assertThat(body.path("code").asText()).isEqualTo(code);
		assertThat(body.path("status").asInt()).isEqualTo(status);
		assertThat(UUID.fromString(body.path("correlationId").asText())).isNotNull();
		assertThat(body.toString()).doesNotContain(TOKEN, "private JDBC", "synthetic-user");
	}
}

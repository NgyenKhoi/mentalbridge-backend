package com.mentalbridge.care.guidedjourney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.configuration.CareJwtProperties;
import com.mentalbridge.care.configuration.IdentityAccountStatusVerifier;
import com.mentalbridge.care.configuration.SecurityConfiguration;
import com.mentalbridge.care.guidedjourney.GuidedJourneyService.EntitlementStatus;
import com.mentalbridge.care.guidedjourney.GuidedJourneyService.JourneyView;
import com.mentalbridge.care.guidedjourney.GuidedJourneyService.Stage;
import com.mentalbridge.care.guidedjourney.HomeNextStepService.Focus;
import com.mentalbridge.care.guidedjourney.HomeNextStepService.HomeNextStepView;
import com.mentalbridge.care.guidedjourney.HomeNextStepService.HomePlanStepView;
import com.mentalbridge.care.guidedjourney.HomeNextStepService.HomeTodayView;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.shared.ApiExceptionHandler;
import com.mentalbridge.care.shared.SecurityProblemSupport;

@WebMvcTest(controllers = GuidedJourneyController.class, properties = "springdotenv.enabled=false")
@ContextConfiguration(classes = { GuidedJourneyController.class, SecurityConfiguration.class,
		SecurityProblemSupport.class, ApiExceptionHandler.class, HomeNextStepControllerTests.JsonConfiguration.class })
class HomeNextStepControllerTests extends CareTestProperties {

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper mapper;
	@MockitoBean GuidedJourneyService journeys;
	@MockitoBean HomeNextStepService home;
	@MockitoBean IdentityAccountStatusVerifier accountStatus;

	@BeforeEach
	void activeAccounts() {
		when(accountStatus.isActive(any(), any())).thenReturn(true);
	}

	@Test
	void enforcesTheSameUserRoleBoundaryAsJourney() throws Exception {
		mvc.perform(get("/api/v1/home-next-step")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		for (String role : java.util.List.of("ROLE_ADMIN", "ROLE_SPECIALIST")) {
			mvc.perform(get("/api/v1/home-next-step").with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
					.authorities(new SimpleGrantedAuthority(role))))
					.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CARE_USER_REQUIRED"));
		}
		verifyNoInteractions(home, journeys);
	}

	@Test
	void forwardsAuthenticatedOwnerBearerCorrelationAndTimezoneWithNoStoreAndUnchangedJourney() throws Exception {
		var owner = UUID.randomUUID();
		var correlation = UUID.randomUUID();
		var journey = firstUse(false);
		when(home.current(owner, "synthetic-home-token", correlation, "Asia/Ho_Chi_Minh"))
				.thenReturn(new HomeNextStepView("mb-home-next-step-v1", journey, null, null));
		when(journeys.current(owner, "synthetic-home-token", correlation)).thenReturn(journey);
		var homeJson = mvc.perform(get("/api/v1/home-next-step").with(user(owner))
				.header("X-Correlation-Id", correlation).param("timezone", "Asia/Ho_Chi_Minh"))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.homePolicyVersion").value("mb-home-next-step-v1"))
				.andExpect(jsonPath("$.journey.stage").value("ASSESSMENT_REQUIRED"))
				.andReturn().getResponse().getContentAsString();
		var journeyJson = mvc.perform(get("/api/v1/guided-journey/current").with(user(owner))
				.header("X-Correlation-Id", correlation)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(mapper.readTree(homeJson).path("journey")).isEqualTo(mapper.readTree(journeyJson));
		assertThat(mapper.readTree(homeJson).properties()).extracting(java.util.Map.Entry::getKey)
				.containsExactlyInAnyOrder("homePolicyVersion", "journey", "plan", "lastCompletedPlan");
		assertThat(homeJson).doesNotContain("\"answers\"", "\"totalScore\"", "\"screeningLevel\"",
				"\"journal\"", "\"emotion\"");
		verify(home).current(owner, "synthetic-home-token", correlation, "Asia/Ho_Chi_Minh");
	}

	@Test
	void serializesThePlanCycleDayAndLengthAsIntegers() throws Exception {
		var owner = UUID.randomUUID();
		when(home.current(eq(owner), eq("synthetic-home-token"), any(), isNull()))
				.thenReturn(new HomeNextStepView("mb-home-next-step-v1", firstUse(false), plan(6, 21), null));

		mvc.perform(get("/api/v1/home-next-step").with(user(owner)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.plan.cycleDay").value(6))
				.andExpect(jsonPath("$.plan.cycleLengthDays").value(21));
	}

	@Test
	void serializesBothRequiredCycleFieldsAsExplicitNullsForAPlanWithoutACycle() throws Exception {
		var owner = UUID.randomUUID();
		when(home.current(eq(owner), eq("synthetic-home-token"), any(), isNull()))
				.thenReturn(new HomeNextStepView("mb-home-next-step-v1", firstUse(false), plan(null, null), null));

		mvc.perform(get("/api/v1/home-next-step").with(user(owner)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.plan.cycleDay").hasJsonPath())
				.andExpect(jsonPath("$.plan.cycleDay").value(nullValue()))
				.andExpect(jsonPath("$.plan.cycleLengthDays").hasJsonPath())
				.andExpect(jsonPath("$.plan.cycleLengthDays").value(nullValue()));
	}

	@Test
	void ownerComesFromTheBearerAndMissingCorrelationIsGenerated() throws Exception {
		var owner = UUID.randomUUID();
		var other = UUID.randomUUID();
		when(home.current(eq(owner), eq("synthetic-home-token"), any(), isNull()))
				.thenReturn(new HomeNextStepView("mb-home-next-step-v1", firstUse(true), null, null));
		when(home.current(eq(other), eq("synthetic-home-token"), any(), isNull()))
				.thenReturn(new HomeNextStepView("mb-home-next-step-v1", firstUse(false), null, null));
		mvc.perform(get("/api/v1/home-next-step").with(user(owner)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.journey.assessmentInProgress").value(true));
		mvc.perform(get("/api/v1/home-next-step").with(user(other)).param("userId", owner.toString()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.journey.assessmentInProgress").value(false));
		var correlation = ArgumentCaptor.forClass(UUID.class);
		verify(home).current(eq(other), eq("synthetic-home-token"), correlation.capture(), isNull());
		assertThat(correlation.getValue()).isNotNull();
	}

	@Test
	void malformedSubjectAndCorrelationAreRejectedBeforeTheService() throws Exception {
		mvc.perform(get("/api/v1/home-next-step").with(jwt().jwt(token -> token.subject("invalid-subject"))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"))))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_token"));
		mvc.perform(get("/api/v1/home-next-step").with(user(UUID.randomUUID()))
				.header("X-Correlation-Id", "invalid-correlation"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		verifyNoInteractions(home, journeys);
	}

	@Test
	void invalidTimezoneRetainsTheServiceValidationProblem() throws Exception {
		var owner = UUID.randomUUID();
		when(home.current(eq(owner), eq("synthetic-home-token"), any(), eq("invalid/timezone")))
				.thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
						"timezone must be a valid IANA time zone"));
		mvc.perform(get("/api/v1/home-next-step").with(user(owner)).param("timezone", "invalid/timezone"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		verify(home).current(eq(owner), eq("synthetic-home-token"), any(), eq("invalid/timezone"));
	}

	@Test
	void missingSupportPolicyRemains503WithTheSuppliedCorrelation() throws Exception {
		var owner = UUID.randomUUID();
		var correlation = UUID.randomUUID();
		when(home.current(owner, "synthetic-home-token", correlation, null)).thenThrow(new ApiException(
				HttpStatus.SERVICE_UNAVAILABLE, "SUPPORT_POLICY_UNAVAILABLE", "No published support policy is available"));
		mvc.perform(get("/api/v1/home-next-step").with(user(owner)).header("X-Correlation-Id", correlation))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SUPPORT_POLICY_UNAVAILABLE"))
				.andExpect(jsonPath("$.correlationId").value(correlation.toString()));
	}

	private HomePlanStepView plan(Integer cycleDay, Integer cycleLengthDays) {
		return new HomePlanStepView(UUID.randomUUID(), "ACTIVE", Focus.NOTHING_TODAY, null,
				cycleDay, cycleLengthDays, LocalDate.parse("2026-10-23"), "Asia/Ho_Chi_Minh",
				new HomeTodayView(0, 0), null);
	}

	private JourneyView firstUse(boolean assessmentInProgress) {
		return new JourneyView("mb-guided-journey-v1", Stage.ASSESSMENT_REQUIRED, assessmentInProgress,
				null, null, null, null, null, null, EntitlementStatus.NOT_REQUIRED);
	}

	private RequestPostProcessor user(UUID owner) {
		return jwt().jwt(token -> token.subject(owner.toString()).tokenValue("synthetic-home-token"))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties(CareJwtProperties.class)
	static class JsonConfiguration {
		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper().findAndRegisterModules();
		}
	}
}

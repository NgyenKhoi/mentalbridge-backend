package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.shared.ApiExceptionHandler;

class NativeActionOccurrenceEngagementControllerTests {

	@Test
	void engagementUsesAuthenticatedSubjectAndReturnsTheUpdatedOccurrenceEtag() {
		var service = mock(NativeActionOccurrenceService.class);
		var controller = new NativeActionOccurrenceController(service);
		var user = UUID.randomUUID();
		var occurrence = UUID.randomUUID();
		var correlation = UUID.randomUUID();
		var value = mock(SupportPlanV3Service.ActionOccurrenceView.class);
		when(value.version()).thenReturn(4L);
		when(service.replaceEngagement(user, occurrence, 3, "COMPLETED", false,
				"HELPFUL", null, null, false, correlation)).thenReturn(value);
		var request = new SupportPlanActivityOccurrenceController.ReplaceEngagementRequest(
				"COMPLETED", false, "HELPFUL", null, null, false);

		var response = controller.engagement(jwt(user.toString()), occurrence, "\"3\"", correlation, request);

		assertThat(response.getBody()).isSameAs(value);
		assertThat(response.getHeaders().getETag()).isEqualTo("\"4\"");
		verify(service).replaceEngagement(user, occurrence, 3, "COMPLETED", false,
				"HELPFUL", null, null, false, correlation);
	}

	@Test
	void deleteUsesAuthenticatedSubjectAndRejectsAnInvalidSubjectBeforeCallingService() {
		var service = mock(NativeActionOccurrenceService.class);
		var controller = new NativeActionOccurrenceController(service);
		var occurrence = UUID.randomUUID();
		assertThatThrownBy(() -> controller.deleteEngagement(jwt("not-a-subject"), occurrence, "\"3\"", null))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
		verifyNoInteractions(service);
		var user = UUID.randomUUID();
		var value = mock(SupportPlanV3Service.ActionOccurrenceView.class);
		when(value.version()).thenReturn(4L);
		when(service.deleteEngagement(user, occurrence, 3, null)).thenReturn(value);

		var response = controller.deleteEngagement(jwt(user.toString()), occurrence, "\"3\"", null);

		assertThat(response.getHeaders().getETag()).isEqualTo("\"4\"");
		verify(service).deleteEngagement(user, occurrence, 3, null);
	}

	@Test
	void overflowingIfMatchRejectsBothEngagementCommandsAndStateBeforeCallingService() {
		var service = mock(NativeActionOccurrenceService.class);
		var controller = new NativeActionOccurrenceController(service);
		var user = jwt(UUID.randomUUID().toString());
		var occurrence = UUID.randomUUID();
		var match = "\"9223372036854775808\"";
		var engagement = new SupportPlanActivityOccurrenceController.ReplaceEngagementRequest(
				"COMPLETED", false, "HELPFUL", null, null, false);

		assertValidationFailure(() -> controller.engagement(user, occurrence, match, null, engagement));
		assertValidationFailure(() -> controller.deleteEngagement(user, occurrence, match, null));
		assertValidationFailure(() -> controller.change(user, occurrence, match, null,
				new NativeActionOccurrenceController.StateRequest("SKIPPED")));
		verifyNoInteractions(service);
	}

	@Test
	void httpRequiresValidIfMatchForBothEngagementVerbs() throws Exception {
		var service = mock(NativeActionOccurrenceService.class);
		var occurrence = UUID.randomUUID();
		try (var validator = new LocalValidatorFactoryBean()) {
			validator.afterPropertiesSet();
			var mvc = mvc(service, validator);
			for (var method : java.util.List.of(HttpMethod.PUT, HttpMethod.DELETE)) {
				for (String match : java.util.List.of("W/\"3\"", "\"text\"", "\"9223372036854775808\"")) {
					mvc.perform(engagementRequest(method, occurrence).header("If-Match", match))
							.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
				}
				mvc.perform(engagementRequest(method, occurrence))
						.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
			}
		}
		verifyNoInteractions(service);
	}

	private MockHttpServletRequestBuilder engagementRequest(HttpMethod method, UUID occurrence) {
		var request = request(method, "/api/v2/support-plan-occurrences/{id}/engagement", occurrence);
		if (method == HttpMethod.PUT) request.contentType(MediaType.APPLICATION_JSON).content("""
				{"state":"COMPLETED","hidden":false,"helpfulness":"HELPFUL","summaryReuseApproved":false}
				""");
		return request;
	}

	private void assertValidationFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable command) {
		assertThatThrownBy(command).isInstanceOfSatisfying(ApiException.class, exception -> {
			assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(exception.code()).isEqualTo("VALIDATION_FAILED");
		});
	}

	private MockMvc mvc(NativeActionOccurrenceService service, LocalValidatorFactoryBean validator) {
		return MockMvcBuilders.standaloneSetup(new NativeActionOccurrenceController(service))
				.setControllerAdvice(new ApiExceptionHandler()).setValidator(validator)
				.setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
					@Override
					public boolean supportsParameter(MethodParameter parameter) {
						return parameter.getParameterType() == Jwt.class;
					}
					@Override
					public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
							NativeWebRequest request, WebDataBinderFactory binderFactory) {
						return jwt("10000000-0000-0000-0000-000000000001");
					}
				}).build();
	}

	private Jwt jwt(String subject) {
		return Jwt.withTokenValue("test-token").header("alg", "RS256").subject(subject).build();
	}
}

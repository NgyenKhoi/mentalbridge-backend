package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.shared.ApiExceptionHandler;

class NativeSupportPlanCycleControllerTests {

	private final NativeSupportPlanService plans = mock(NativeSupportPlanService.class);
	private final NativeSupportPlanController controller = new NativeSupportPlanController(plans);
	private final UUID user = UUID.randomUUID();
	private final UUID plan = UUID.randomUUID();
	private final String key = "cycle-continuation-command-0001";

	@Test
	void continuationUsesAuthenticatedSubjectAndReturnsThePlanEtag() {
		var value = mock(SupportPlanV3Service.SupportPlanV3View.class);
		when(value.version()).thenReturn(4L);
		UUID correlation = UUID.randomUUID();
		when(plans.continueCycle(user, plan, 3, "test-token", key, correlation)).thenReturn(value);

		var response = controller.continueCycle(jwt(user.toString()), plan, "\"3\"", key, correlation);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isSameAs(value);
		assertThat(response.getHeaders().getETag()).isEqualTo("\"4\"");
		// The caller's bearer reaches the entitlement revalidation.
		verify(plans).continueCycle(user, plan, 3, "test-token", key, correlation);
	}

	@Test
	void invalidAuthenticatedSubjectNeverCallsService() {
		assertThatThrownBy(() -> controller.continueCycle(jwt("invalid-subject"), plan, "\"3\"", key, null))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
					assertThat(exception.code()).isEqualTo("UNAUTHENTICATED");
				});
		verifyNoInteractions(plans);
	}

	@Test
	void malformedOrOverflowingIfMatchReturns400WithoutCallingService() {
		for (String match : java.util.List.of("W/\"3\"", "3", "\"-1\"", "\"text\"",
				"\"9223372036854775808\"")) {
			assertThatThrownBy(() -> controller.continueCycle(jwt(user.toString()), plan, match, key, null))
					.isInstanceOfSatisfying(ApiException.class, exception -> {
						assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
						assertThat(exception.code()).isEqualTo("VALIDATION_FAILED");
					});
		}
		verifyNoInteractions(plans);
	}

	@Test
	void httpRejectsMalformedAndOverflowingVersionsAndInvalidRetryKeys() throws Exception {
		try (var validator = new LocalValidatorFactoryBean()) {
			validator.afterPropertiesSet();
			var mvc = mvc(validator);
			for (String match : java.util.List.of("W/\"3\"", "\"text\"", "\"9223372036854775808\"")) {
				mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan)
						.header("If-Match", match).header("Idempotency-Key", key))
						.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
			}
			for (String retryKey : java.util.List.of("too-short", "cycle-continuation-command-\u00e9", "cycle command with spaces")) {
				mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan)
						.header("If-Match", "\"3\"").header("Idempotency-Key", retryKey))
						.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
			}
		}
		verifyNoInteractions(plans);
	}

	@Test
	void httpRequiresBothConcurrencyAndRetryHeaders() throws Exception {
		try (var validator = new LocalValidatorFactoryBean()) {
			validator.afterPropertiesSet();
			var mvc = mvc(validator);
			mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan).header("Idempotency-Key", key))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
			mvc.perform(post("/api/v2/support-plans/{id}/cycle-continuation", plan).header("If-Match", "\"3\""))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		verifyNoInteractions(plans);
	}

	private MockMvc mvc(LocalValidatorFactoryBean validator) {
		return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())
				.setValidator(validator).setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
					@Override
					public boolean supportsParameter(MethodParameter parameter) {
						return parameter.getParameterType() == Jwt.class;
					}
					@Override
					public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
							NativeWebRequest request, WebDataBinderFactory binderFactory) {
						return jwt(user.toString());
					}
				}).build();
	}

	private Jwt jwt(String subject) {
		return Jwt.withTokenValue("test-token").header("alg", "RS256").subject(subject).build();
	}
}

package com.mentalbridge.care.configuration;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import com.mentalbridge.care.consent.VoiceConsentProperties;
import com.mentalbridge.care.supportplan.SupportPlanReminderEligibilityController;
import com.mentalbridge.care.supportplan.SupportPlanReminderProperties;

/** Content-only service credential for the SupportPlan reminder read; user JWTs never authorize it. */
@Configuration(proxyBeanMethods = false)
public class SupportPlanReminderSecurityConfiguration {
	public static final String TOKEN_HEADER = "X-MentalBridge-Service-Token";

	@Bean
	@Order(2)
	SecurityFilterChain supportPlanReminderSecurityFilterChain(HttpSecurity http,
			SupportPlanReminderProperties properties, VoiceConsentProperties voice,
			SupportPlanCompanionClientProperties companion) throws Exception {
		properties.requireDistinctCredential(voice.journalServiceToken(), voice.idempotencyHmacKey(),
				companion.serviceToken());
		var unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
		http.securityMatcher(SupportPlanReminderEligibilityController.PATH)
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.addFilterBefore(new OncePerRequestFilter() {
					@Override
					protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
							FilterChain chain) throws ServletException, IOException {
						response.setHeader("Cache-Control", "no-store");
						chain.doFilter(request, response);
					}
				}, SecurityContextHolderFilter.class)
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(HttpMethod.POST, SupportPlanReminderEligibilityController.PATH)
						.access((authentication, context) -> new AuthorizationDecision(
								properties.authorizes(context.getRequest().getHeader(TOKEN_HEADER))))
						.anyRequest().denyAll())
				.exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized)
						.accessDeniedHandler((request, response, exception) ->
								response.setStatus(HttpStatus.UNAUTHORIZED.value())));
		return http.build();
	}
}

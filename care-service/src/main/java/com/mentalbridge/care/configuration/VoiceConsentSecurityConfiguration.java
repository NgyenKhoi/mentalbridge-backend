package com.mentalbridge.care.configuration;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import com.mentalbridge.care.consent.VoiceConsentProperties;
import com.mentalbridge.care.consent.VoiceProblemSupport;

@Configuration(proxyBeanMethods = false)
public class VoiceConsentSecurityConfiguration {
	public static final String AUTHORIZATION_PATH = "/internal/v1/ai-companion/voice-consent-authorizations";

	@Bean
	@Order(1)
	SecurityFilterChain voiceConsentSecurityFilterChain(HttpSecurity http,
			Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter,
			VoiceConsentProperties properties, SupportPlanCompanionClientProperties companionProperties,
			VoiceProblemSupport problems) throws Exception {
		properties.requireDistinctCredential(companionProperties.serviceToken());
		http.securityMatcher("/api/v1/ai-voice-processing-disclosure", "/api/v1/consents/ai-voice-processing",
				AUTHORIZATION_PATH)
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
				.addFilterAfter(new OncePerRequestFilter() {
					@Override
					protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
							FilterChain chain) throws ServletException, IOException {
						if (!validTransport(request)) {
							problems.invalidRequest(request, response);
							return;
						}
						chain.doFilter(request, response);
					}
				}, AuthorizationFilter.class)
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(AUTHORIZATION_PATH).access((authentication, context) -> new AuthorizationDecision(
								properties.authorizes(context.getRequest().getHeader("X-MentalBridge-Voice-Care-Token"))))
						.anyRequest().hasRole("USER"))
				.exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
				.oauth2ResourceServer(resourceServer -> resourceServer.authenticationEntryPoint(problems)
						.accessDeniedHandler(problems).jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
		return http.build();
	}

	private boolean validTransport(HttpServletRequest request) {
		var method = request.getMethod();
		var path = request.getRequestURI().substring(request.getContextPath().length());
		var allowed = switch (path) {
		case AUTHORIZATION_PATH -> method.equals("POST");
		case "/api/v1/ai-voice-processing-disclosure" -> method.equals("GET");
		default -> method.equals("GET") || method.equals("POST");
		};
		if (!allowed || !method.equals("POST")) return allowed;
		if (request.getContentType() == null) return false;
		try {
			var type = org.springframework.http.MediaType.parseMediaType(request.getContentType());
			return type.getType().equals("application") && type.getSubtype().equals("json");
		}
		catch (IllegalArgumentException exception) { return false; }
	}
}

package com.mentalbridge.care.configuration;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.mentalbridge.care.shared.SecurityProblemSupport;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter,
			SecurityProblemSupport securityProblems) throws Exception {
		http.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers("/actuator/health", "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
						.requestMatchers("/api/v1/questionnaires/**", "/api/v1/privacy-disclosures/**",
								"/api/v1/anonymous-assessment-sessions/**")
						.permitAll()
						.requestMatchers("/api/v1/profile/**", "/api/v1/consents/**",
								"/api/v1/consent-decisions/**", "/api/v1/assessments/**",
								"/api/v1/support-evaluations/**").hasRole("USER")
						.anyRequest().authenticated())
				.exceptionHandling(errors -> errors.authenticationEntryPoint(securityProblems)
						.accessDeniedHandler(securityProblems))
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
		return http.build();
	}

	@Bean
	JwtDecoder jwtDecoder(CareJwtProperties properties) {
		var decoder = NimbusJwtDecoder.withPublicKey(publicKey(properties.publicKey()))
				.signatureAlgorithm(SignatureAlgorithm.RS256).build();
		var issuer = JwtValidators.createDefaultWithIssuer(properties.issuer());
		var audience = new JwtClaimValidator<java.util.List<String>>("aud",
				audiences -> audiences != null && audiences.contains(properties.audience()));
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
		return decoder;
	}

	@Bean
	Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
		var authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName("roles");
		authorities.setAuthorityPrefix("ROLE_");
		var converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}

	private RSAPublicKey publicKey(String pem) {
		try {
			var bytes = Base64.getDecoder().decode(stripPem(pem));
			return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
		}
		catch (Exception exception) {
			throw new IllegalStateException("Care JWT public key is invalid", exception);
		}
	}

	private String stripPem(String pem) {
		return pem.replace("\\n", "\n").replace("-----BEGIN PUBLIC KEY-----", "")
				.replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
	}
}

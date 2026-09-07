package com.mentalbridge.identity.configuration;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
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
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.mentalbridge.identity.authentication.JwtTokenService;
import com.mentalbridge.identity.shared.SecurityProblemSupport;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter,
			SecurityProblemSupport securityProblems) throws Exception {
		http.csrf(csrf -> csrf.disable()).sessionManagement(session -> session
				.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.requestMatchers("/api/v1/auth/registrations", "/api/v1/auth/email-verifications",
						"/api/v1/auth/email-verification-requests", "/api/v1/auth/login", "/api/v1/auth/refresh",
						"/api/v1/auth/password-recovery-requests", "/api/v1/auth/password-resets")
				.permitAll().requestMatchers("/api/v1/admin/**").hasRole("ADMIN").anyRequest().authenticated())
				.exceptionHandling(errors -> errors.authenticationEntryPoint(securityProblems)
						.accessDeniedHandler(securityProblems))
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
		return http.build();
	}

	@Bean
	JwtEncoder jwtEncoder(JwtProperties properties) {
		var rsaKey = new RSAKey.Builder(publicKey(properties.publicKey())).privateKey(privateKey(properties.privateKey()))
				.algorithm(com.nimbusds.jose.JWSAlgorithm.RS256).keyID(properties.keyId()).build();
		return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(rsaKey)));
	}

	@Bean
	JwtDecoder jwtDecoder(JwtProperties properties) {
		var decoder = NimbusJwtDecoder.withPublicKey(publicKey(properties.publicKey()))
				.signatureAlgorithm(SignatureAlgorithm.RS256).build();
		var issuer = JwtValidators.createDefaultWithIssuer(properties.issuer());
		var audience = new JwtClaimValidator<java.util.List<String>>("aud",
				audiences -> audiences != null && audiences.contains(properties.audience()));
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
		return decoder;
	}

	@Bean
	JwtTokenService jwtTokenService(JwtEncoder encoder, JwtProperties properties) {
		return new JwtTokenService(encoder, properties);
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
			var bytes = Base64.getDecoder().decode(stripPem(pem, "PUBLIC KEY"));
			return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
		}
		catch (Exception exception) {
			throw new IllegalStateException("Identity JWT public key is invalid", exception);
		}
	}

	private RSAPrivateKey privateKey(String pem) {
		try {
			var bytes = Base64.getDecoder().decode(stripPem(pem, "PRIVATE KEY"));
			return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
		}
		catch (Exception exception) {
			throw new IllegalStateException("Identity JWT private key is invalid", exception);
		}
	}

	private String stripPem(String pem, String type) {
		return pem.replace("\\n", "\n").replace("-----BEGIN " + type + "-----", "")
				.replace("-----END " + type + "-----", "").replaceAll("\\s", "");
	}

}

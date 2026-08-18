package com.mentalbridge.identity.authentication;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

import com.mentalbridge.identity.account.RoleCode;
import com.mentalbridge.identity.configuration.JwtProperties;

public class JwtTokenService {

	private static final Duration ACCESS_LIFETIME = Duration.ofMinutes(15);

	private final JwtEncoder encoder;
	private final JwtProperties properties;

	public JwtTokenService(JwtEncoder encoder, JwtProperties properties) {
		this.encoder = encoder;
		this.properties = properties;
	}

	public IssuedAccessToken issue(UUID accountId, Set<RoleCode> roles, Instant issuedAt) {
		var claims = JwtClaimsSet.builder().issuer(properties.issuer()).subject(accountId.toString())
				.audience(java.util.List.of(properties.audience())).issuedAt(issuedAt).notBefore(issuedAt)
				.expiresAt(issuedAt.plus(ACCESS_LIFETIME)).id(UUID.randomUUID().toString())
				.claim("roles", roles.stream().map(Enum::name).sorted().toList()).build();
		var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(properties.keyId()).build();
		var token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedAccessToken(token, ACCESS_LIFETIME.toSeconds());
	}

	public record IssuedAccessToken(String value, long expiresInSeconds) {
	}

}

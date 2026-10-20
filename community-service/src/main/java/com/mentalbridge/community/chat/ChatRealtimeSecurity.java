package com.mentalbridge.community.chat;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import com.mentalbridge.community.shared.SecurityProblemSupport;

@Configuration(proxyBeanMethods = false)
class ChatRealtimeSecurity {
    @Bean
    @Order(1)
    SecurityFilterChain communityRealtimeSecurity(HttpSecurity http, ChatRealtimeProperties properties,
            SecurityProblemSupport problems, Clock clock) throws Exception {
        JwtDecoder decoder = token -> { throw new BadJwtException("Community realtime is disabled"); };
        if (properties.enabled()) {
            var pem = properties.publicKey().replace("\\n", "\n").replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            RSAPublicKey key;
            try { key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem))); }
            catch (Exception exception) { throw new IllegalArgumentException("Community realtime verification key is invalid"); }
            var signed = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
            signed.setJwtValidator(jwt -> valid(jwt, properties.keyId(), clock)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token")));
            decoder = signed;
        }
        http.securityMatcher("/internal/v1/community/chat/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems));
        final var trusted = decoder;
        http.oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(trusted)).authenticationEntryPoint(problems));
        return http.build();
    }
    static boolean valid(Jwt jwt, String keyId, Clock clock) {
        try {
            var now = clock.instant(); var issued = jwt.getIssuedAt(); var expiry = jwt.getExpiresAt();
            return keyId.equals(jwt.getHeaders().get("kid")) && "realtime-service".equals(jwt.getClaimAsString("iss"))
                    && jwt.getAudience().equals(java.util.List.of("community-service"))
                    && jwt.getSubject() != null && ChatModels.uuid(jwt.getSubject()) != null
                    && ChatModels.uuid(jwt.getId()) != null
                    && issued != null && expiry != null && !issued.isAfter(now) && expiry.isAfter(now)
                    && expiry.isAfter(issued) && !expiry.isAfter(issued.plusSeconds(30));
        } catch (RuntimeException exception) { return false; }
    }
    static java.util.UUID actor(Jwt jwt, String scope, java.util.UUID conversation, java.util.UUID message) {
        var claims = new java.util.HashSet<>(java.util.Set.of("iss", "aud", "sub", "act", "scope", "jti", "iat", "exp"));
        if (conversation != null) claims.add("conversationId");
        if (message != null) claims.add("messageId");
        if (!Map.of("sub", "realtime-service").equals(jwt.getClaimAsMap("act")) || !scope.equals(jwt.getClaimAsString("scope")))
            throw ChatException.forbidden();
        if (!jwt.getClaims().keySet().equals(claims)) throw ChatException.forbidden();
        if (conversation != null && !conversation.toString().equals(jwt.getClaimAsString("conversationId"))) throw ChatException.target();
        if (message != null && !message.toString().equals(jwt.getClaimAsString("messageId"))) throw ChatException.target();
        return ChatModels.uuid(jwt.getSubject());
    }
}

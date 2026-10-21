package com.mentalbridge.community.chat;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import com.mentalbridge.community.shared.SecurityProblemSupport;

@Configuration(proxyBeanMethods = false)
class ChatNotificationSecurity {
    static final String PATH = "/internal/v1/community/chat/notification-authorizations";
    @Bean @Order(0)
    SecurityFilterChain notificationSecurity(HttpSecurity http, Properties properties, Clock clock,
            SecurityProblemSupport problems) throws Exception {
        JwtDecoder decoder = token -> { throw new BadJwtException("Community notification authorization is disabled"); };
        if (properties.enabled()) {
            RSAPublicKey key;
            try {
                var pem = properties.publicKey().replace("\\n", "\n").replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
                key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
            } catch (Exception exception) { throw new IllegalArgumentException("Community notification verification key is invalid"); }
            var signed = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
            signed.setJwtValidator(jwt -> valid(jwt, properties.keyId(), clock)
                    ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token")));
            decoder = signed;
        }
        final var trusted = decoder;
        http.securityMatcher(PATH).csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST, PATH)
                        .hasAuthority("SCOPE_community:notification:authorize").anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(trusted)).authenticationEntryPoint(problems));
        return http.build();
    }
    static boolean valid(Jwt jwt, String keyId, Clock clock) {
        try {
            var issued = jwt.getIssuedAt(); var expiry = jwt.getExpiresAt(); var now = clock.instant();
            return keyId.equals(jwt.getHeaders().get("kid")) && "content-notification-service".equals(jwt.getClaimAsString("iss"))
                    && "content-notification-service".equals(jwt.getSubject()) && jwt.getAudience().equals(List.of("community-service"))
                    && ChatModels.uuid(jwt.getId()) != null && issued != null && expiry != null && !issued.isAfter(now)
                    && expiry.isAfter(now) && expiry.isAfter(issued) && !expiry.isAfter(issued.plusSeconds(30))
                    && jwt.getClaims().keySet().equals(Set.of("iss", "sub", "aud", "scope", "jti", "iat", "exp"));
        } catch (RuntimeException exception) { return false; }
    }
    @ConfigurationProperties("mentalbridge.community.chat-notification")
    public record Properties(@DefaultValue("false") boolean enabled, @DefaultValue("") String publicKey,
            @DefaultValue("") String keyId) {
        public Properties {
            if (enabled && (publicKey == null || publicKey.isBlank() || keyId == null || keyId.isBlank()))
                throw new IllegalArgumentException("Enabled Community notification authorization requires a dedicated Content verification key");
        }
        @Override public String toString() { return "ChatNotificationProperties[enabled=" + enabled + ", key=REDACTED]"; }
    }
}

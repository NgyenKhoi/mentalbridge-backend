package com.mentalbridge.community.chat;

import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.community.chat")
public record ChatProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("") String identityBaseUrl, @DefaultValue("") String identityServiceToken,
        @DefaultValue("PT0.5S") Duration connectTimeout, @DefaultValue("PT1.5S") Duration readTimeout,
        @DefaultValue("") String encryptionKey, @DefaultValue("v1") String encryptionKeyVersion,
        @DefaultValue("{}") String decryptionKeys) {
    public ChatProperties {
        if (connectTimeout == null || readTimeout == null || connectTimeout.isNegative()
                || connectTimeout.isZero() || readTimeout.isNegative() || readTimeout.isZero()
                || connectTimeout.plus(readTimeout).compareTo(Duration.ofSeconds(2)) > 0) {
            throw new IllegalArgumentException("Community chat owner deadline must be positive and at most two seconds");
        }
        if (enabled) {
            try {
                if (identityServiceToken == null || !identityServiceToken.matches("[!-~]{32,512}")
                        || encryptionKey == null || Base64.getDecoder().decode(encryptionKey).length != 32
                        || encryptionKeyVersion == null || !encryptionKeyVersion.matches("[A-Za-z0-9_-]{1,40}")) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Enabled Community chat requires a dedicated Identity credential and versioned 32-byte encryption key");
            }
        }
    }
    void requireEnabled() { if (!enabled) throw ChatException.unavailable(); }
    @Override public String toString() { return "ChatProperties[enabled=" + enabled + ", secrets=REDACTED]"; }
}

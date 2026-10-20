package com.mentalbridge.community.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("mentalbridge.community.chat-realtime")
public record ChatRealtimeProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("") String publicKey, @DefaultValue("") String keyId) {
    public ChatRealtimeProperties {
        if (enabled && (publicKey == null || publicKey.isBlank() || keyId == null || keyId.isBlank()))
            throw new IllegalArgumentException("Enabled Community realtime requires a trusted Realtime verification key");
    }
    @Override public String toString() { return "ChatRealtimeProperties[enabled=" + enabled + ", key=REDACTED]"; }
}

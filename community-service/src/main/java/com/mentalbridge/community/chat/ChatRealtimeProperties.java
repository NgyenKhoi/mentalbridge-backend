package com.mentalbridge.community.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Realtime delegation key; required whenever chat is enabled (chat realtime has no separate switch). */
@ConfigurationProperties("mentalbridge.community.chat-realtime")
public record ChatRealtimeProperties(@DefaultValue("") String publicKey, @DefaultValue("") String keyId) {
    @Override public String toString() { return "ChatRealtimeProperties[key=REDACTED]"; }
}

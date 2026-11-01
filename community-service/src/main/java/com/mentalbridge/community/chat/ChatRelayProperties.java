package com.mentalbridge.community.chat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("mentalbridge.community.chat-relay")
public record ChatRelayProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("100") int batchSize, @DefaultValue("PT5S") Duration interval) {
    static final String TOPIC = "mentalbridge.community.chat.v1";
    public ChatRelayProperties {
        if (batchSize < 1 || batchSize > 500 || interval == null
                || interval.compareTo(Duration.ofMillis(100)) < 0 || interval.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Community chat relay requires a bounded batch and interval");
        }
    }
}

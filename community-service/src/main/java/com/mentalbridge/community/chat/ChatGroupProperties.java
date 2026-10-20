package com.mentalbridge.community.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("mentalbridge.community.chat-group")
public record ChatGroupProperties(@DefaultValue("30") int memberLimit) {
    public ChatGroupProperties {
        if (memberLimit < 20 || memberLimit > 30) throw new IllegalArgumentException("Community group cap must be 20–30");
    }
}

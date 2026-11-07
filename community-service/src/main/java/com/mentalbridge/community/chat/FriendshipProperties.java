package com.mentalbridge.community.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("mentalbridge.community.friends")
public record FriendshipProperties(@DefaultValue("false") boolean enabled) {
    public void requireEnabled() {
        if (!enabled) throw ChatException.unavailable();
    }
}

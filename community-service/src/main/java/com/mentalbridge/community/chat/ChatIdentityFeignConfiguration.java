package com.mentalbridge.community.chat;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import org.springframework.context.annotation.Bean;

public class ChatIdentityFeignConfiguration {
    @Bean Request.Options chatIdentityDeadlines(ChatProperties properties) {
        return new Request.Options(properties.connectTimeout(), properties.readTimeout(), false);
    }
    @Bean Retryer chatIdentityRetryer() { return Retryer.NEVER_RETRY; }
    @Bean Logger.Level chatIdentityLogLevel() { return Logger.Level.NONE; }
}

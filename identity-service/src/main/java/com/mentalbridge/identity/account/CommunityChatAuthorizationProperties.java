package com.mentalbridge.identity.account;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("mentalbridge.identity.community-chat")
public record CommunityChatAuthorizationProperties(
        @Pattern(regexp = "(?:|[!-~]{32,512})") String serviceToken) {
    public CommunityChatAuthorizationProperties {
        serviceToken = serviceToken == null ? "" : serviceToken;
    }

    @Override
    public String toString() {
        return "CommunityChatAuthorizationProperties[serviceToken=REDACTED]";
    }
}

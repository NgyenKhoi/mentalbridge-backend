package com.mentalbridge.community.chat;

import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "identity-service", contextId = "communityChatIdentity",
        url = "${mentalbridge.community.chat.identity-base-url:}", configuration = ChatIdentityFeignConfiguration.class)
interface ChatIdentityHttpClient {
    @PostMapping("/internal/v1/community-chat/account-authorizations")
    Result authorize(@RequestHeader("X-MentalBridge-Service-Token") String credential,
            @RequestHeader("X-Correlation-Id") String correlationId, @RequestBody Query query);
    record Query(List<UUID> accountIds) {
        @Override public String toString() { return "ChatAccountQuery[REDACTED]"; }
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Result(List<UUID> activeUserAccountIds) {
        @Override public String toString() { return "ChatAccountResult[REDACTED]"; }
    }
}

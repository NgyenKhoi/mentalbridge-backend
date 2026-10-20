package com.mentalbridge.identity.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class CommunityChatAccountAuthorizationController {
    private final AccountRepository accounts;
    private final byte[] serviceToken;

    CommunityChatAccountAuthorizationController(AccountRepository accounts,
            CommunityChatAuthorizationProperties properties) {
        this.accounts = accounts;
        this.serviceToken = properties.serviceToken().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/v1/community-chat/account-authorizations")
    @Transactional(readOnly = true)
    ResponseEntity<AuthorizationResult> authorize(@Valid @RequestBody AuthorizationRequest request,
            @RequestHeader(value = "X-MentalBridge-Service-Token", required = false) String candidate) {
        if (serviceToken.length < 32 || candidate == null || !MessageDigest.isEqual(serviceToken,
                candidate.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        var subjects = request.accountIds().stream().distinct().toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AuthorizationResult(accounts.findActiveChatUsers(subjects)));
    }

    record AuthorizationRequest(@NotEmpty @Size(max = 100) List<@NotNull UUID> accountIds) {
        @Override public String toString() { return "CommunityChatAuthorizationRequest[REDACTED]"; }
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    record AuthorizationResult(List<UUID> activeUserAccountIds) {
        @Override public String toString() { return "CommunityChatAuthorizationResult[REDACTED]"; }
    }
}

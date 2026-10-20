package com.mentalbridge.community.chat;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/community/chat")
class CommunityChatRealtimeController {
    private final CommunityDirectChatService direct;
    private final CommunityChatMessageService messages;
    private final CommunityChatPresenceService presence;
    CommunityChatRealtimeController(CommunityDirectChatService direct, CommunityChatMessageService messages,
            CommunityChatPresenceService presence) {
        this.direct = direct; this.messages = messages; this.presence = presence;
    }
    @PostMapping("/session-authorizations")
    Authorization session(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value="X-Correlation-Id", required=false) String correlation) {
        direct.privacy(ChatRealtimeSecurity.actor(jwt, "community:session:authorize", null, null), correlation(correlation));
        return new Authorization(true, "0");
    }
    @PostMapping("/realtime-authorizations")
    Authorization authorize(@AuthenticationPrincipal Jwt jwt, @RequestBody Subscribe input,
            @RequestHeader(value="X-Correlation-Id", required=false) String correlation) {
        if (input.conversationId() == null || !"SUBSCRIBE".equals(input.action())) throw ChatException.invalid();
        direct.conversation(ChatRealtimeSecurity.actor(jwt, "community:subscribe", input.conversationId(), null), input.conversationId(), correlation(correlation));
        return new Authorization(true, "0");
    }
    @PostMapping("/message-projections")
    ChatModels.Message message(@AuthenticationPrincipal Jwt jwt, @RequestBody MessageTarget input,
            @RequestHeader(value="X-Correlation-Id", required=false) String correlation) {
        if (input.conversationId() == null || input.messageId() == null) throw ChatException.invalid();
        return messages.projection(ChatRealtimeSecurity.actor(jwt, "community:message:project", input.conversationId(), input.messageId()),
                input.conversationId(), input.messageId(), correlation(correlation));
    }
    @PostMapping("/presence-projections")
    ResponseEntity<ChatPresenceTransactions.Projection> presence(@AuthenticationPrincipal Jwt jwt, @RequestBody PresenceTarget input,
            @RequestHeader(value="X-Correlation-Id", required=false) String correlation) {
        if (input.conversationId() == null) throw ChatException.invalid();
        var subject = ChatRealtimeSecurity.actor(jwt, "community:presence:project", input.conversationId(), null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(presence.projection(subject, input.conversationId(), correlation(correlation)));
    }
    @PostMapping("/replay-projections")
    ChatDeliveryCursor.Projection replay(@AuthenticationPrincipal Jwt jwt, @RequestBody ReplayTarget input,
            @RequestHeader(value="X-Correlation-Id", required=false) String correlation) {
        if (input.conversationId() == null) throw ChatException.invalid();
        return messages.replay(ChatRealtimeSecurity.actor(jwt, "community:replay:project", input.conversationId(), null),
                input.conversationId(), input.cursor(), correlation(correlation));
    }
    private UUID correlation(String value) { return CommunityChatController.correlation(value); }
    record Authorization(boolean allowed, String decisionVersion) {}
    record Subscribe(UUID conversationId, String action) {
        @JsonAnySetter public void unknown(String key, Object value) { throw ChatException.invalid(); }
    }
    record MessageTarget(UUID conversationId, UUID messageId) {
        @JsonAnySetter public void unknown(String key, Object value) { throw ChatException.invalid(); }
    }
    record PresenceTarget(UUID conversationId) {
        @JsonAnySetter public void unknown(String key, Object value) { throw ChatException.invalid(); }
        @Override public String toString() { return "PresenceTarget[REDACTED]"; }
    }
    record ReplayTarget(UUID conversationId, String cursor) {
        @JsonAnySetter public void unknown(String key, Object value) { throw ChatException.invalid(); }
    }
}

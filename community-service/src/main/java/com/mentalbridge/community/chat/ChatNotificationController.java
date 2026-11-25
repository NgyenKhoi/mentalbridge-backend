package com.mentalbridge.community.chat;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class ChatNotificationController {
    private final ChatNotificationService service;
    ChatNotificationController(ChatNotificationService service) { this.service = service; }
    @PostMapping(ChatNotificationSecurity.PATH)
    ResponseEntity<Decision> authorize(@RequestBody Target target,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        if (target.recipientRoutingReference() == null || target.kind() == null || target.targetId() == null
                || target.conversationId() == null || target.actorCommunityProfileId() == null) throw ChatException.invalid();
        service.authorize(target, CommunityChatController.correlation(correlation));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Decision(true, "0"));
    }
    enum Kind { MESSAGE_RECEIVED, DIRECT_REQUESTED, GROUP_INVITED }
    record Target(UUID recipientRoutingReference, Kind kind, UUID targetId, UUID conversationId, UUID actorCommunityProfileId) {
        @JsonAnySetter public void unknown(String key, Object value) { throw ChatException.invalid(); }
        @Override public String toString() { return "NotificationTarget[REDACTED]"; }
    }
    record Decision(boolean allowed, String decisionVersion) {}
}

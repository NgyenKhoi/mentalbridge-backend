package com.mentalbridge.community.chat;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/community/chat")
class CommunityChatController {
    private final CommunityDirectChatService chat;
    private final CommunityChatMessageService messages;
    CommunityChatController(CommunityDirectChatService chat, CommunityChatMessageService messages) { this.chat = chat; this.messages = messages; }
    @GetMapping("/privacy")
    ResponseEntity<ChatModels.PrivacyView> privacy(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(chat.privacy(subject(jwt), correlation(correlation)));
    }
    @PutMapping("/privacy")
    ResponseEntity<ChatModels.PrivacyView> privacy(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "If-Match", required = false) String version, @RequestBody ChatModels.Privacy request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        var result = chat.privacy(subject(jwt), ChatModels.version(version), request, correlation(correlation));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.version()).body(result);
    }
    @PostMapping("/direct-conversations")
    ResponseEntity<ChatModels.DirectResult> start(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody ChatModels.StartDirect request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(chat.start(subject(jwt), request.recipientCommunityProfileId(), ChatModels.uuid(key), correlation(correlation)));
    }
    @GetMapping("/requests")
    ChatModels.Page<ChatModels.Request> requests(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        return chat.incoming(subject(jwt), cursor, limit, correlation(correlation));
    }
    @PostMapping("/requests/{requestId}/decision")
    ResponseEntity<ChatModels.DirectResult> decide(@AuthenticationPrincipal Jwt jwt, @PathVariable String requestId,
            @RequestHeader(value = "If-Match", required = false) String version, @RequestBody ChatModels.RequestDecision request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        var result = chat.decide(subject(jwt), ChatModels.uuid(requestId), ChatModels.version(version), request.decision(), correlation(correlation));
        return result.body() == null ? ResponseEntity.noContent().build() : ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(result.version()).body(result.body());
    }
    @GetMapping("/conversations")
    ChatModels.Page<ChatModels.Conversation> conversations(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        return chat.conversations(subject(jwt), cursor, limit, correlation(correlation));
    }
    @GetMapping("/conversations/{conversationId}")
    ChatModels.Conversation conversation(@AuthenticationPrincipal Jwt jwt, @PathVariable String conversationId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        return chat.conversation(subject(jwt), ChatModels.uuid(conversationId), correlation(correlation));
    }
    @PostMapping("/conversations/{conversationId}/messages")
    ResponseEntity<ChatModels.SendResult> send(@AuthenticationPrincipal Jwt jwt, @PathVariable String conversationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody ChatModels.SendMessage request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(messages.send(subject(jwt),
                ChatModels.uuid(conversationId), ChatModels.uuid(key), request, correlation(correlation)));
    }
    @GetMapping("/conversations/{conversationId}/messages")
    ChatModels.Page<ChatModels.Message> history(@AuthenticationPrincipal Jwt jwt, @PathVariable String conversationId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        return messages.history(subject(jwt), ChatModels.uuid(conversationId), cursor, limit, correlation(correlation));
    }
    @PutMapping("/conversations/{conversationId}/read-cursor")
    ChatModels.ReadResult read(@AuthenticationPrincipal Jwt jwt, @PathVariable String conversationId,
            @RequestBody ChatModels.ReadCursor request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        return messages.read(subject(jwt), ChatModels.uuid(conversationId), request.lastReadMessageId(), correlation(correlation));
    }
    static UUID subject(Jwt jwt) {
        if (jwt == null) throw ChatException.target();
        return ChatModels.uuid(jwt.getSubject());
    }
    static UUID correlation(String value) {
        try { return value == null ? UUID.randomUUID() : ChatModels.uuid(value); }
        catch (RuntimeException exception) { return UUID.randomUUID(); }
    }
}

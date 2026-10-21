package com.mentalbridge.community.feed;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.mentalbridge.community.shared.CommunityApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/community/comments/{commentId}/reaction")
class CommunityCommentReactionController {
    private final CommunityCommentReactionService service;
    CommunityCommentReactionController(CommunityCommentReactionService service) { this.service = service; }
    @GetMapping
    ResponseEntity<CommunityCommentReactionService.View> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID commentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(subject(jwt), commentId));
    }
    @PutMapping
    ResponseEntity<CommunityCommentReactionService.View> put(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID commentId, @Valid @RequestBody Input input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.put(subject(jwt), commentId, input.reaction()));
    }
    @DeleteMapping
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID commentId) {
        service.delete(subject(jwt), commentId); return ResponseEntity.noContent().build();
    }
    record Input(@NotNull CommunityPostReactionEntity.Reaction reaction) {
        @JsonAnySetter public void unknown(String name, Object value) { throw CommunityApiException.invalidCommentInput(); }
    }
    private UUID subject(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException exception) { throw CommunityApiException.invalidSubject(); }
    }
}

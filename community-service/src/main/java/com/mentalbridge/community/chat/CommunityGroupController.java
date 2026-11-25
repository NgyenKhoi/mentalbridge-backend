package com.mentalbridge.community.chat;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/community/chat")
class CommunityGroupController {
    private final CommunityGroupService groups;
    CommunityGroupController(CommunityGroupService groups) { this.groups = groups; }
    @PostMapping("/groups")
    ResponseEntity<ChatGroupModels.Group> create(@AuthenticationPrincipal Jwt jwt, @RequestBody ChatGroupModels.Create body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        var result = groups.create(CommunityChatController.subject(jwt), body.name(), ChatModels.uuid(key), CommunityChatController.correlation(correlation));
        return ResponseEntity.status(201).eTag(result.version()).body(result);
    }
    @PostMapping("/groups/{conversationId}/invitations")
    ResponseEntity<ChatGroupModels.Invitation> invite(@AuthenticationPrincipal Jwt jwt, @PathVariable String conversationId,
            @RequestBody ChatGroupModels.Invite body, @RequestHeader(value = "If-Match", required = false) String version,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        var result = groups.invite(CommunityChatController.subject(jwt), ChatModels.uuid(conversationId), body.inviteeCommunityProfileId(), ChatModels.version(version), ChatModels.uuid(key), CommunityChatController.correlation(correlation));
        return ResponseEntity.status(201).eTag(Long.toString(result.version())).body(result.body());
    }
    @GetMapping("/invitations")
    ChatModels.Page<ChatGroupModels.Invitation> incoming(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit, @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        return groups.incoming(CommunityChatController.subject(jwt), cursor, limit, CommunityChatController.correlation(correlation));
    }
    @PostMapping("/invitations/{invitationId}/decision")
    ResponseEntity<ChatGroupModels.Group> decide(@AuthenticationPrincipal Jwt jwt, @PathVariable String invitationId, @RequestBody ChatGroupModels.Decision body,
            @RequestHeader(value = "If-Match", required = false) String version, @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        var result = groups.decide(CommunityChatController.subject(jwt), ChatModels.uuid(invitationId), body.decision(), ChatModels.version(version), CommunityChatController.correlation(correlation));
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok().eTag(result.version()).body(result);
    }
    @GetMapping("/groups/{conversationId}/members")
    ChatModels.Page<ChatGroupModels.Member> members(@AuthenticationPrincipal Jwt jwt, @PathVariable String conversationId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        return groups.members(CommunityChatController.subject(jwt), ChatModels.uuid(conversationId), cursor, limit, CommunityChatController.correlation(correlation));
    }
    @PutMapping("/groups/{conversationId}/members/{communityProfileId}/role")
    ResponseEntity<ChatGroupModels.Member> role(@AuthenticationPrincipal Jwt jwt, @PathVariable String conversationId, @PathVariable String communityProfileId,
            @RequestBody ChatGroupModels.Role body, @RequestHeader(value = "If-Match", required = false) String version,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        var result = groups.role(CommunityChatController.subject(jwt), ChatModels.uuid(conversationId), ChatModels.uuid(communityProfileId), body.role(), ChatModels.version(version), CommunityChatController.correlation(correlation));
        return ResponseEntity.ok().eTag(Long.toString(result.version())).body(result.body());
    }
    @PostMapping("/groups/{conversationId}/ownership")
    ResponseEntity<ChatGroupModels.Group> transfer(@AuthenticationPrincipal Jwt jwt, @PathVariable String conversationId, @RequestBody ChatGroupModels.Transfer body,
            @RequestHeader(value = "If-Match", required = false) String version, @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        var result = groups.transfer(CommunityChatController.subject(jwt), ChatModels.uuid(conversationId), body.newOwnerCommunityProfileId(), ChatModels.version(version), CommunityChatController.correlation(correlation));
        return ResponseEntity.ok().eTag(result.version()).body(result);
    }
    @DeleteMapping("/groups/{conversationId}/members/{communityProfileId}")
    ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable String conversationId, @PathVariable String communityProfileId,
            @RequestHeader(value = "If-Match", required = false) String version, @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        groups.remove(CommunityChatController.subject(jwt), ChatModels.uuid(conversationId), ChatModels.uuid(communityProfileId), ChatModels.version(version), CommunityChatController.correlation(correlation));
        return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/groups/{conversationId}")
    ResponseEntity<Void> archive(@AuthenticationPrincipal Jwt jwt, @PathVariable String conversationId,
            @RequestHeader(value = "If-Match", required = false) String version, @RequestHeader(value = "X-Correlation-Id", required = false) String correlation) {
        groups.archive(CommunityChatController.subject(jwt), ChatModels.uuid(conversationId), ChatModels.version(version), CommunityChatController.correlation(correlation));
        return ResponseEntity.noContent().build();
    }
}

package com.mentalbridge.community.chat;

import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/community/circles")
class CommunityCircleController {
    private final CommunityCircleService circles;
    CommunityCircleController(CommunityCircleService circles) { this.circles = circles; }
    @GetMapping
    ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) UUID cursor, @RequestParam(defaultValue="10") int limit) {
        return ok(circles.list(actor(jwt, List.of()), cursor, limit));
    }
    @PostMapping
    ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key, @RequestBody CommunityCircleService.Create body) {
        if (body.invitees() == null || body.invitees().size() > 19 || body.invitees().contains(null)) throw ChatException.invalid();
        return ok(circles.create(actor(jwt, body.invitees()), body, key));
    }
    @GetMapping("/{circleId}")
    ResponseEntity<?> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID circleId) { return ok(circles.detail(actor(jwt,List.of()), circleId)); }
    @DeleteMapping("/{circleId}")
    ResponseEntity<Void> archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID circleId) { circles.archive(actor(jwt,List.of()), circleId); return empty(); }
    @PostMapping("/{circleId}/invitations")
    ResponseEntity<Void> invite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID circleId, @RequestBody Invite body) {
        if (body.profileId() == null) throw ChatException.invalid();
        circles.invite(actor(jwt,List.of(body.profileId())), circleId, body.profileId()); return empty();
    }
    @PostMapping("/{circleId}/decision")
    ResponseEntity<Void> decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID circleId, @RequestBody Decision body) {
        if (body.decision() == null) throw ChatException.invalid(); circles.decide(actor(jwt,List.of()),circleId,body.decision()); return empty();
    }
    @DeleteMapping("/{circleId}/members/{profileId}")
    ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID circleId, @PathVariable UUID profileId) { circles.removeMember(actor(jwt,List.of()),circleId,profileId); return empty(); }
    @GetMapping("/{circleId}/posts")
    ResponseEntity<?> posts(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID circleId, @RequestParam(required=false) UUID cursor, @RequestParam(defaultValue="10") int limit) { return ok(circles.entries(actor(jwt,List.of()),circleId,null,cursor,limit)); }
    @PostMapping("/{circleId}/posts")
    ResponseEntity<?> publish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID circleId, @RequestBody Write body, @RequestHeader("Idempotency-Key") UUID key) { return ok(circles.publish(actor(jwt,List.of()),circleId,null,body.text(),key)); }
    @GetMapping("/{circleId}/posts/{entryId}/comments")
    ResponseEntity<?> comments(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID circleId, @PathVariable UUID entryId, @RequestParam(required=false) UUID cursor, @RequestParam(defaultValue="10") int limit) { return ok(circles.entries(actor(jwt,List.of()),circleId,entryId,cursor,limit)); }
    @PostMapping("/{circleId}/posts/{entryId}/comments")
    ResponseEntity<?> comment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID circleId, @PathVariable UUID entryId, @RequestBody Write body, @RequestHeader("Idempotency-Key") UUID key) { return ok(circles.publish(actor(jwt,List.of()),circleId,entryId,body.text(),key)); }
    @DeleteMapping({"/{circleId}/posts/{entryId}","/{circleId}/comments/{entryId}"})
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID circleId, @PathVariable UUID entryId) { circles.delete(actor(jwt,List.of()),circleId,entryId); return empty(); }
    private UUID actor(Jwt jwt,List<UUID> targets) { var subject = CommunityChatController.subject(jwt); circles.authorize(subject,targets,UUID.randomUUID()); return subject; }
    private <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    private ResponseEntity<Void> empty() { return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build(); }
    record Invite(UUID profileId) {}
    record Decision(String decision) {}
    record Write(String text) { @Override public String toString() { return "CircleWrite[REDACTED]"; } }
}

package com.mentalbridge.community.chat;

import java.util.List;
import java.util.UUID;
import com.mentalbridge.community.feed.CommunityMemberService;
import com.mentalbridge.community.feed.CommunityFeedService;
import com.mentalbridge.community.feed.CommunityResponses;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/community")
class CommunitySocialController {
    private final CommunityFriendshipService friends;
    private final FriendshipTransactions relationships;
    private final CommunityMemberService members;
    private final CommunityFeedService feed;
    private final FriendshipProperties properties;
    private final ChatAccountAuthorizer accounts;

    CommunitySocialController(CommunityFriendshipService friends, FriendshipTransactions relationships,
            CommunityMemberService members, CommunityFeedService feed, FriendshipProperties properties, ChatAccountAuthorizer accounts) {
        this.friends = friends; this.relationships = relationships; this.members = members;
        this.feed = feed; this.properties = properties; this.accounts = accounts;
    }

    @GetMapping("/chat/friends")
    ResponseEntity<FriendshipTransactions.Page> friends(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "accepted") String view, @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return noStore(friends.list(subject(jwt), view, cursor, limit, UUID.randomUUID()));
    }

    @PutMapping("/chat/friends/{profileId}")
    ResponseEntity<FriendshipTransactions.Link> request(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID profileId) {
        return noStore(friends.request(subject(jwt), profileId, UUID.randomUUID()));
    }

    @PostMapping("/chat/friendships/{friendshipId}/accept")
    ResponseEntity<FriendshipTransactions.Link> accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID friendshipId) {
        return noStore(friends.accept(subject(jwt), friendshipId, UUID.randomUUID()));
    }

    @DeleteMapping("/chat/friendships/{friendshipId}")
    ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID friendshipId) {
        friends.remove(subject(jwt), friendshipId, UUID.randomUUID());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/members")
    ResponseEntity<CommunityMemberService.MemberPage> search(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String q, @RequestParam(required = false) UUID cursor, @RequestParam(defaultValue = "20") int limit) {
        properties.requireEnabled();
        ChatModels.limit(limit);
        var query = q.strip();
        if (query.codePointCount(0, query.length()) < 2 || query.codePointCount(0, query.length()) > 80) throw ChatException.invalid();
        accounts.require(List.of(subject(jwt)), UUID.randomUUID());
        return noStore(members.search(subject(jwt), query, cursor, limit));
    }

    @GetMapping("/members/{profileId}")
    ResponseEntity<MemberProfile> member(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID profileId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        properties.requireEnabled();
        ChatModels.limit(limit);
        var author = members.profile(subject(jwt), profileId).orElseThrow(ChatException::target);
        var relationship = relationships.relationship(subject(jwt), profileId);
        return noStore(new MemberProfile(author, relationship, feed.memberPosts(subject(jwt), profileId, cursor, limit)));
    }

    private UUID subject(Jwt jwt) { return CommunityChatController.subject(jwt); }
    private <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    record MemberProfile(CommunityResponses.Author member, FriendshipTransactions.State friendship, CommunityResponses.Feed posts) {}
}

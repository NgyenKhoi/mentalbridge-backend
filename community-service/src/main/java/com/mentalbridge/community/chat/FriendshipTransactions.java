package com.mentalbridge.community.chat;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import com.mentalbridge.community.feed.CommunityResponses.Author;
import com.mentalbridge.community.feed.CommunityMemberService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FriendshipTransactions {
    private final FriendshipRepository friendships;
    private final CommunityChatProfiles profiles;
    private final ChatAccess access;
    private final FriendshipProperties properties;
    private final Clock clock;
    private final CommunityMemberService members;

    FriendshipTransactions(FriendshipRepository friendships, CommunityChatProfiles profiles, ChatAccess access,
            FriendshipProperties properties, Clock clock, CommunityMemberService members) {
        this.friendships = friendships; this.profiles = profiles; this.access = access;
        this.properties = properties; this.clock = clock;
        this.members = members;
    }

    @Transactional
    public State relationship(UUID subject, UUID target) {
        properties.requireEnabled();
        var actor = profiles.ensure(subject);
        if (actor.id().equals(target)) {
            access.lock(List.of(actor.id()));
            return new State("SELF", null);
        }
        access.pair(actor.id(), target);
        return pair(actor.id(), target).map(f -> new State(f.viewerState(actor.id()), f.id)).orElse(new State("NONE", null));
    }

    @Transactional
    Link request(UUID subject, UUID target) {
        properties.requireEnabled();
        var actor = profiles.ensure(subject);
        access.pair(actor.id(), target);
        var pair = ordered(actor.id(), target);
        var friendship = friendships.findByFirstProfileIdAndSecondProfileId(pair.get(0), pair.get(1))
                .orElseGet(() -> friendships.saveAndFlush(new FriendshipEntity(pair.get(0), pair.get(1), actor.id(), clock.instant())));
        return view(friendship, actor.id());
    }

    @Transactional
    Link accept(UUID subject, UUID id) {
        properties.requireEnabled();
        var actor = profiles.ensure(subject);
        var target = target(id, actor.id());
        access.pair(actor.id(), target);
        var friendship = friendships.owned(actor.id(), id).orElseThrow(ChatException::target);
        if (friendship.requesterProfileId.equals(actor.id())) throw ChatException.target();
        if (!friendship.state.equals("ACCEPTED")) {
            friendship.state = "ACCEPTED";
            friendship.acceptedAt = clock.instant();
        }
        return view(friendship, actor.id());
    }

    @Transactional
    void remove(UUID subject, UUID id) {
        properties.requireEnabled();
        var actor = profiles.ensure(subject);
        var target = target(id, actor.id());
        profiles.lock(ordered(actor.id(), target));
        friendships.owned(actor.id(), id).ifPresent(friendships::delete);
    }

    @Transactional
    Page list(UUID subject, String view, UUID cursor, int limit) {
        properties.requireEnabled();
        if (!List.of("accepted", "incoming", "outgoing").contains(view)) throw ChatException.invalid();
        ChatModels.limit(limit);
        var actor = access.actor(subject);
        var rows = friendships.page(actor.id(), view, cursor, PageRequest.of(0, limit + 1));
        var items = rows.stream().limit(limit).map(f -> view(f, actor.id())).toList();
        return new Page(items, rows.size() > limit ? rows.get(limit - 1).id : null);
    }

    UUID target(UUID id, UUID actor) {
        return friendships.target(actor, id).orElseThrow(ChatException::target);
    }

    private java.util.Optional<FriendshipEntity> pair(UUID first, UUID second) {
        var ordered = ordered(first, second);
        return friendships.findByFirstProfileIdAndSecondProfileId(ordered.get(0), ordered.get(1));
    }

    private List<UUID> ordered(UUID first, UUID second) {
        return List.of(first, second).stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    private Link view(FriendshipEntity friendship, UUID actor) {
        var peer = members.visibleProfile(actor, friendship.other(actor)).orElseThrow(ChatException::target);
        return new Link(friendship.id, peer,
                friendship.viewerState(actor), friendship.createdAt);
    }

    public record State(String state, UUID friendshipId) {}
    public record Link(UUID friendshipId, Author peer, String state, Instant createdAt) {}
    public record Page(List<Link> items, UUID nextCursor) {}
}

package com.mentalbridge.community.chat;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import com.mentalbridge.community.feed.CommunityChatProfiles.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatConversationListing {
    private final ChatConversationRepository conversations;
    private final ChatMembershipRepository memberships;
    private final CommunityChatProfiles profiles;
    private final ChatPrivacyRepository privacy;
    private final ChatGroupAccess groups;
    private final ChatGroupMessageTransactions groupMessages;
    private final ChatMessageRepository messages;
    private final ChatUnread unread;
    private final ChatCursors cursors;
    private final Clock clock;
    private final ChatHistoryVisibility visibility;
    ChatConversationListing(ChatConversationRepository conversations, ChatMembershipRepository memberships,
            CommunityChatProfiles profiles, ChatPrivacyRepository privacy, ChatGroupAccess groups,
            ChatGroupMessageTransactions groupMessages, ChatMessageRepository messages, ChatUnread unread, ChatCursors cursors,
            Clock clock, ChatHistoryVisibility visibility) {
        this.conversations = conversations; this.memberships = memberships; this.profiles = profiles; this.privacy = privacy;
        this.groups = groups; this.groupMessages = groupMessages; this.messages = messages; this.unread = unread; this.cursors = cursors; this.clock = clock;
        this.visibility = visibility;
    }
    @Transactional(readOnly = true)
    Snapshot snapshot(UUID subject) {
        var actor = profiles.bySubject(subject).orElse(null);
        if (actor == null) return new Snapshot(null, List.of(), List.of());
        var values = conversations.visibleTo(actor.id());
        var ids = new HashSet<UUID>(); ids.add(actor.id());
        for (var value : values) {
            if (value.kind.equals("DIRECT")) { ids.add(value.firstProfileId); ids.add(value.secondProfileId); }
            else {
                memberships.findByConversationIdAndDepartedAtIsNullOrderById(value.id).forEach(m -> ids.add(m.profileId));
                ids.addAll(messages.retainedGroupSenders(value.id, clock.instant()));
            }
        }
        return new Snapshot(actor.id(), values.stream().map(v -> v.id).toList(), profiles.byIds(ids));
    }
    @Transactional
    ChatModels.Page<ChatModels.ConversationView> list(Snapshot snapshot, Set<UUID> active, String cursor, int limit) {
        if (snapshot.actor() == null) { if (cursor != null) throw ChatException.target(); return new ChatModels.Page<>(List.of(), null); }
        var current = profiles.lock(snapshot.profiles().stream().map(Profile::id).toList()).stream().collect(Collectors.toMap(Profile::id, p -> p));
        var actor = current.get(snapshot.actor());
        if (actor == null || !actor.active() || !active.contains(actor.subject()) || privacy.restricted(List.of(actor.id()))) throw ChatException.target();
        var epoch = privacy.findById(actor.id()).map(p -> p.visibilityEpoch).orElse(0L);
        var before = cursors.decode(actor.id(), "conversations", epoch, cursor);
        var authorized = new ChatGroupAccess.Authorization(actor.id(), new ArrayList<>(current.keySet()),
                current.values().stream().filter(p -> active.contains(p.subject())).map(Profile::id).collect(Collectors.toSet()));
        var selected = new ArrayList<Row>();
        for (var id : snapshot.conversations().stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
            var conversation = conversations.lock(id).orElse(null); if (conversation == null) continue;
            ChatModels.ConversationView view; Instant activity;
            if (conversation.kind.equals("GROUP")) {
                if (memberships.findByConversationIdAndProfileIdAndDepartedAtIsNull(id, actor.id()).isEmpty()) continue;
                var context = groups.lock(id, authorized, true);
                var state = visibility.state(id, actor.id());
                long floor = Math.max(context.member().joinedAfterSequence, state.clearedThroughSequence);
                var recent = messages.groupActivity(id, groupMessages.visible(context), floor, clock.instant());
                if (state.clearedAt != null && recent.isEmpty()) continue;
                view = groupMessages.conversation(context);
                activity = recent.orElse(context.member().joinedAt);
            } else {
                if (!conversation.state.equals("ACTIVE")) continue;
                var peer = current.get(conversation.other(actor.id()));
                if (peer == null || !peer.active() || !active.contains(peer.subject()) || privacy.restricted(List.of(peer.id())) || privacy.blocked(actor.id(), peer.id())) continue;
                var state = visibility.state(id, actor.id());
                var recent = messages.directActivity(id, state.clearedThroughSequence, clock.instant());
                if (state.clearedAt != null && recent.isEmpty()) continue;
                view = new ChatModels.Conversation(id, "DIRECT", "ACTIVE", List.of(new ChatModels.Peer("AVAILABLE", actor.id(), actor.displayName()),
                        new ChatModels.Peer("AVAILABLE", peer.id(), peer.displayName())), unread.count(id, actor.id()), Long.toString(conversation.managementVersion));
                activity = recent.orElse(conversation.activatedAt);
            }
            if (activity.isBefore(before.time()) || (activity.equals(before.time()) && id.toString().compareTo(before.id().toString()) < 0)) selected.add(new Row(id, activity, view));
        }
        selected.sort(Comparator.comparing(Row::time).thenComparing(r -> r.id().toString()).reversed());
        var page = selected.stream().limit(limit).toList(); var last = page.isEmpty() ? null : page.getLast();
        return new ChatModels.Page<>(page.stream().map(Row::view).toList(), selected.size() > limit ? cursors.encode(actor.id(), "conversations", epoch, last.time(), last.id()) : null);
    }
    record Snapshot(UUID actor, List<UUID> conversations, List<Profile> profiles) {}
    private record Row(UUID id, Instant time, ChatModels.ConversationView view) {}
}

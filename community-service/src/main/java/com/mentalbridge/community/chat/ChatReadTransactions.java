package com.mentalbridge.community.chat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import com.mentalbridge.community.feed.CommunityChatProfiles.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatReadTransactions {
    private final CommunityChatProfiles profiles;
    private final ChatAccess access;
    private final ChatConversationRepository conversations;
    private final ChatRequestRepository requests;
    private final ChatPrivacyRepository privacy;
    private final ChatCursors cursors;
    private final Clock clock;
    ChatReadTransactions(CommunityChatProfiles profiles, ChatAccess access, ChatConversationRepository conversations,
            ChatRequestRepository requests, ChatPrivacyRepository privacy, ChatCursors cursors, Clock clock) {
        this.profiles = profiles; this.access = access; this.conversations = conversations;
        this.requests = requests; this.privacy = privacy; this.cursors = cursors; this.clock = clock;
    }
    @Transactional
    Anchor anchor(UUID subject, String scope, String cursor) {
        var actor = profiles.bySubject(subject);
        if (actor.isEmpty()) {
            if (cursor != null) throw ChatException.target();
            return null;
        }
        access.lock(List.of(actor.orElseThrow().id()));
        var epoch = access.privacy(actor.orElseThrow().id()).visibilityEpoch;
        return new Anchor(actor.orElseThrow().id(), epoch, cursors.decode(actor.orElseThrow().id(), scope, epoch, cursor));
    }
    @Transactional(readOnly = true)
    List<Candidate> requestCandidates(Anchor anchor, ChatCursors.Position before) {
        return requests.candidates(anchor.viewer(), clock.instant(), before.time(), before.id(), 50).stream()
                .map(request -> candidate(request.id, request.requesterProfileId, request.createdAt)).toList();
    }
    @Transactional(readOnly = true)
    List<Candidate> conversationCandidates(Anchor anchor, ChatCursors.Position before) {
        return conversations.candidates(anchor.viewer(), before.time(), before.id(), 50).stream()
                .map(conversation -> candidate(conversation.id, conversation.other(anchor.viewer()), conversation.activatedAt)).toList();
    }
    private Candidate candidate(UUID id, UUID peerId, java.time.Instant time) {
        var peer = profiles.byId(peerId).orElseThrow(ChatException::target);
        return new Candidate(id, peer.id(), peer.subject(), new ChatCursors.Position(time, id));
    }
    @Transactional
    List<ChatModels.Request> requests(UUID subject, List<Candidate> candidates, Set<UUID> activeSubjects) {
        var actor = profiles.bySubject(subject).orElseThrow(ChatException::target);
        var current = locked(actor.id(), candidates);
        var results = new ArrayList<ChatModels.Request>();
        for (var candidate : candidates) {
            var peer = current.get(candidate.peerId());
            if (!eligible(actor.id(), peer, activeSubjects)) continue;
            var request = requests.findById(candidate.id()).orElse(null);
            if (request == null || !request.recipientProfileId.equals(actor.id()) || !request.pending(clock.instant())) continue;
            results.add(new ChatModels.Request(request.id, access.peer(peer), request.state, request.createdAt, request.expiresAt,
                    Long.toString(request.version)));
        }
        return results;
    }
    @Transactional
    List<ChatModels.Conversation> conversations(UUID subject, List<Candidate> candidates, Set<UUID> activeSubjects) {
        var actor = profiles.bySubject(subject).orElseThrow(ChatException::target);
        var current = locked(actor.id(), candidates);
        var results = new ArrayList<ChatModels.Conversation>();
        for (var candidate : candidates) {
            var peer = current.get(candidate.peerId());
            if (!eligible(actor.id(), peer, activeSubjects)) continue;
            var conversation = conversations.findById(candidate.id()).orElse(null);
            if (conversation == null || !conversation.state.equals("ACTIVE") || !conversation.other(actor.id()).equals(peer.id())) continue;
            results.add(view(conversation, current.get(actor.id()), peer));
        }
        return results;
    }
    @Transactional
    ChatModels.Conversation conversation(UUID subject, UUID conversationId) {
        var actor = profiles.bySubject(subject).orElseThrow(ChatException::target);
        var initial = conversations.findById(conversationId).orElseThrow(ChatException::target);
        var pair = access.pair(actor.id(), initial.other(actor.id()));
        var conversation = conversations.findById(conversationId).orElseThrow(ChatException::target);
        if (!conversation.state.equals("ACTIVE")) throw ChatException.target();
        var peer = pair.stream().filter(profile -> !profile.id().equals(actor.id())).findFirst().orElseThrow();
        return view(conversation, actor, peer);
    }
    private ChatModels.Conversation view(ChatConversationEntity value, Profile actor, Profile peer) {
        return new ChatModels.Conversation(value.id, "DIRECT", "ACTIVE", List.of(access.peer(actor), access.peer(peer)),
                0, Long.toString(value.managementVersion));
    }
    private Map<UUID, Profile> locked(UUID actor, List<Candidate> candidates) {
        var ids = new ArrayList<UUID>(); ids.add(actor);
        candidates.forEach(candidate -> ids.add(candidate.peerId()));
        var current = profiles.lock(ids).stream().collect(Collectors.toMap(Profile::id, Function.identity()));
        var owner = current.get(actor);
        if (owner == null || !owner.active() || privacy.restricted(List.of(actor))) throw ChatException.target();
        return current;
    }
    private boolean eligible(UUID actor, Profile peer, Set<UUID> activeSubjects) {
        return peer != null && peer.active() && activeSubjects.contains(peer.subject())
                && !privacy.restricted(List.of(peer.id())) && !privacy.blocked(actor, peer.id());
    }
    record Anchor(UUID viewer, long epoch, ChatCursors.Position position) {}
    record Candidate(UUID id, UUID peerId, UUID peerSubject, ChatCursors.Position position) {}
}

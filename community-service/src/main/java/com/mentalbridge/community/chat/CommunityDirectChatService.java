package com.mentalbridge.community.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import org.springframework.stereotype.Service;

@Service
class CommunityDirectChatService {
    private final ChatAccountAuthorizer accounts;
    private final CommunityChatProfiles profiles;
    private final ChatPrivacyTransactions privacy;
    private final ChatDirectTransactions direct;
    private final ChatReadTransactions reads;
    private final ChatRequestRepository requests;
    private final ChatConversationRepository conversations;
    private final ChatCursors cursors;
    CommunityDirectChatService(ChatAccountAuthorizer accounts, CommunityChatProfiles profiles,
            ChatPrivacyTransactions privacy, ChatDirectTransactions direct, ChatReadTransactions reads,
            ChatRequestRepository requests, ChatConversationRepository conversations, ChatCursors cursors) {
        this.accounts = accounts; this.profiles = profiles; this.privacy = privacy; this.direct = direct;
        this.reads = reads; this.requests = requests; this.conversations = conversations; this.cursors = cursors;
    }
    ChatModels.PrivacyView privacy(UUID subject, UUID correlation) {
        accounts.require(List.of(subject), correlation); return privacy.get(subject);
    }
    ChatModels.PrivacyView privacy(UUID subject, long expected, ChatModels.Privacy input, UUID correlation) {
        input.validate(); accounts.require(List.of(subject), correlation); return privacy.replace(subject, expected, input, correlation);
    }
    ChatModels.DirectResult start(UUID subject, UUID targetId, UUID key, UUID correlation) {
        if (targetId == null) throw ChatException.invalid();
        var target = profiles.byId(targetId).filter(p -> p.active() && !p.subject().equals(subject)).orElseThrow(ChatException::target);
        accounts.require(List.of(subject, target.subject()), correlation);
        return direct.start(subject, targetId, target.subject(), key, correlation);
    }
    DecisionOutcome decide(UUID subject, UUID requestId, long expected, String decision, UUID correlation) {
        var actor = profiles.bySubject(subject).orElseThrow(ChatException::target);
        var request = requests.findById(requestId).filter(r -> r.recipientProfileId.equals(actor.id())).orElseThrow(ChatException::target);
        var sender = profiles.byId(request.requesterProfileId).orElseThrow(ChatException::target);
        accounts.require(List.of(subject, sender.subject()), correlation);
        var result = direct.decide(subject, requestId, request.requesterProfileId, expected, decision, correlation);
        return new DecisionOutcome(result, Long.toString(requests.findById(requestId).orElseThrow(ChatException::target).version));
    }
    ChatModels.Conversation conversation(UUID subject, UUID id, UUID correlation) {
        var actor = profiles.bySubject(subject).orElseThrow(ChatException::target);
        var value = conversations.findById(id).orElseThrow(ChatException::target);
        var peer = profiles.byId(value.other(actor.id())).orElseThrow(ChatException::target);
        accounts.require(List.of(subject, peer.subject()), correlation);
        return reads.conversation(subject, id);
    }
    ChatModels.Page<ChatModels.Request> incoming(UUID subject, String cursor, int limit, UUID correlation) {
        ChatModels.limit(limit); accounts.require(List.of(subject), correlation);
        var anchor = reads.anchor(subject, "requests", cursor);
        if (anchor == null) return new ChatModels.Page<>(List.of(), null);
        var selected = new ArrayList<ChatModels.Request>();
        var before = anchor.position();
        while (selected.size() <= limit) {
            var batch = reads.requestCandidates(anchor, before);
            if (batch.isEmpty()) break;
            var ids = new ArrayList<UUID>(); ids.add(subject); batch.forEach(candidate -> ids.add(candidate.peerSubject()));
            var active = accounts.active(ids, correlation);
            if (!active.contains(subject)) throw ChatException.target();
            selected.addAll(reads.requests(subject, batch, active));
            before = batch.getLast().position();
            if (batch.size() < 50) break;
        }
        boolean more = selected.size() > limit;
        var items = selected.stream().limit(limit).toList();
        var last = items.isEmpty() ? null : items.getLast();
        return new ChatModels.Page<>(items, more ? cursors.encode(anchor.viewer(), "requests", anchor.epoch(), last.createdAt(), last.requestId()) : null);
    }
    ChatModels.Page<ChatModels.Conversation> conversations(UUID subject, String cursor, int limit, UUID correlation) {
        ChatModels.limit(limit); accounts.require(List.of(subject), correlation);
        var anchor = reads.anchor(subject, "conversations", cursor);
        if (anchor == null) return new ChatModels.Page<>(List.of(), null);
        var selected = new ArrayList<ChatModels.Conversation>();
        var positions = new java.util.HashMap<UUID, ChatCursors.Position>();
        var before = anchor.position();
        while (selected.size() <= limit) {
            var batch = reads.conversationCandidates(anchor, before);
            if (batch.isEmpty()) break;
            var ids = new ArrayList<UUID>(); ids.add(subject); batch.forEach(candidate -> ids.add(candidate.peerSubject()));
            var active = accounts.active(ids, correlation);
            if (!active.contains(subject)) throw ChatException.target();
            selected.addAll(reads.conversations(subject, batch, active));
            batch.forEach(candidate -> positions.put(candidate.id(), candidate.position()));
            before = batch.getLast().position();
            if (batch.size() < 50) break;
        }
        boolean more = selected.size() > limit;
        var items = selected.stream().limit(limit).toList();
        var last = items.isEmpty() ? null : positions.get(items.getLast().conversationId());
        return new ChatModels.Page<>(items, more ? cursors.encode(anchor.viewer(), "conversations", anchor.epoch(), last.time(), last.id()) : null);
    }
    record DecisionOutcome(ChatModels.DirectResult body, String version) {}
}

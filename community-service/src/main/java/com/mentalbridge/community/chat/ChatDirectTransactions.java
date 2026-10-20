package com.mentalbridge.community.chat;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatDirectTransactions {
    private final CommunityChatProfiles profiles;
    private final ChatAccess access;
    private final ChatConversationRepository conversations;
    private final ChatRequestRepository requests;
    private final ChatCommandRepository commands;
    private final ChatCrypto crypto;
    private final ChatFacts facts;
    private final CommunityChatVisibility visibility;
    private final Clock clock;
    ChatDirectTransactions(CommunityChatProfiles profiles, ChatAccess access, ChatConversationRepository conversations,
            ChatRequestRepository requests, ChatCommandRepository commands, ChatCrypto crypto, ChatFacts facts,
            CommunityChatVisibility visibility, Clock clock) {
        this.profiles = profiles; this.access = access; this.conversations = conversations; this.requests = requests;
        this.commands = commands; this.crypto = crypto; this.facts = facts; this.visibility = visibility; this.clock = clock;
    }
    @Transactional
    ChatModels.DirectResult start(UUID subject, UUID targetId, UUID expectedTargetSubject, UUID key, UUID correlation) {
        var actor = profiles.ensure(subject);
        var locked = access.pair(actor.id(), targetId);
        var target = locked.stream().filter(p -> p.id().equals(targetId)).findFirst().orElseThrow(ChatException::target);
        if (!target.subject().equals(expectedTargetSubject)) throw ChatException.target();
        var replay = commands.findByActorProfileIdAndOperationAndIdempotencyKey(actor.id(), "START_DIRECT", key);
        if (replay.isPresent() && !crypto.matches(replay.orElseThrow().requestFingerprint, "START_DIRECT", targetId.toString()))
            throw ChatException.conflict();
        var pair = List.of(actor.id(), targetId).stream().sorted(Comparator.comparing(UUID::toString)).toList();
        var conversation = conversations.findByFirstProfileIdAndSecondProfileId(pair.get(0), pair.get(1))
                .orElseGet(() -> conversations.saveAndFlush(new ChatConversationEntity(pair.get(0), pair.get(1), clock.instant())));
        ChatModels.DirectResult result;
        if (conversation.state.equals("ACTIVE")) result = ChatModels.DirectResult.active(conversation.id);
        else {
            if (requests.existsByConversationIdAndRequesterProfileIdAndStateIn(conversation.id, actor.id(), List.of("DECLINED", "BLOCKED")))
                throw ChatException.notAllowed();
            var pending = requests.findByConversationIdAndState(conversation.id, "PENDING");
            for (var old : pending) if (!old.pending(clock.instant())) old.decide("EXPIRED", clock.instant());
            var current = pending.stream().filter(request -> request.pending(clock.instant())).findFirst();
            if (current.isPresent()) result = ChatModels.DirectResult.pending(current.orElseThrow().id);
            else if (replay.isPresent()) throw ChatException.notAllowed();
            else if (access.privacy(target.id()).directMessagePolicy.equals("ANYONE")) {
                conversation.activate(clock.instant()); facts.invalidated(conversation, correlation);
                result = ChatModels.DirectResult.active(conversation.id);
            } else {
                if (requests.countByRequesterProfileIdAndCreatedAtAfter(actor.id(), clock.instant().minus(Duration.ofDays(1))) >= 5)
                    throw ChatException.limited();
                requests.flush();
                var request = requests.saveAndFlush(new ChatRequestEntity(conversation.id, actor.id(), target.id(), clock.instant()));
                conversation.eventVersion++;
                facts.publish(conversation, "DIRECT_REQUESTED", request.id, actor.id(), target.subject(), correlation, null);
                result = ChatModels.DirectResult.pending(request.id);
            }
        }
        if (replay.isEmpty()) commands.save(new ChatCommandEntity(actor.id(), "START_DIRECT", key,
                crypto.fingerprint("START_DIRECT", targetId.toString()), result.state(),
                result.conversationId() == null ? result.requestId() : result.conversationId(), clock.instant()));
        return result;
    }

    @Transactional
    ChatModels.DirectResult decide(UUID subject, UUID requestId, UUID requester, long expected, String decision, UUID correlation) {
        if (!List.of("ACCEPT", "DECLINE", "BLOCK").contains(decision == null ? "" : decision)) throw ChatException.invalid();
        var actor = profiles.bySubject(subject).orElseThrow(ChatException::target);
        access.lock(List.of(actor.id(), requester));
        var request = requests.findById(requestId).orElseThrow(ChatException::target);
        if (!request.recipientProfileId.equals(actor.id())) throw ChatException.target();
        if (!request.requesterProfileId.equals(requester)) throw ChatException.target();
        var resulting = switch (decision) { case "ACCEPT" -> "ACCEPTED"; case "DECLINE" -> "DECLINED"; default -> "BLOCKED"; };
        var conversation = conversations.findById(request.conversationId).orElseThrow(ChatException::target);
        if (!request.state.equals("PENDING")) {
            if (!actor.id().equals(request.decidedByProfileId)) throw ChatException.target();
            if (!request.state.equals(resulting)) throw ChatException.version();
            if (resulting.equals("ACCEPTED")) access.pair(actor.id(), requester);
            return resulting.equals("ACCEPTED") ? ChatModels.DirectResult.active(conversation.id) : null;
        }
        access.pair(actor.id(), requester);
        if (!request.pending(clock.instant())) throw ChatException.notAllowed();
        if (request.version != expected) throw ChatException.version();
        request.decide(resulting, clock.instant());
        request.decidedByProfileId = actor.id();
        if (resulting.equals("ACCEPTED")) conversation.activate(clock.instant());
        facts.invalidated(conversation, correlation);
        if (decision.equals("BLOCK")) visibility.changeBlock(actor.id(), request.requesterProfileId, true, correlation);
        return resulting.equals("ACCEPTED") ? ChatModels.DirectResult.active(conversation.id) : null;
    }
}

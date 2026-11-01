package com.mentalbridge.community.chat;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatClearTransactions {
    private final ChatConversationRepository conversations;
    private final ChatGroupAccess groups;
    private final ChatCommandRepository commands;
    private final ChatHistoryVisibility visibility;
    private final ChatCrypto crypto;
    private final ChatFacts facts;
    private final Clock clock;
    ChatClearTransactions(ChatConversationRepository conversations, ChatGroupAccess groups, ChatCommandRepository commands,
            ChatHistoryVisibility visibility, ChatCrypto crypto, ChatFacts facts, Clock clock) {
        this.conversations = conversations; this.groups = groups; this.commands = commands; this.visibility = visibility;
        this.crypto = crypto; this.facts = facts; this.clock = clock;
    }
    @Transactional
    void clearDirect(UUID conversationId, UUID actor, UUID peer, UUID subject, UUID key, UUID correlation) {
        var conversation = conversations.lock(conversationId).filter(value -> value.kind.equals("DIRECT")
                && value.state.equals("ACTIVE") && value.other(actor).equals(peer)).orElseThrow(ChatException::target);
        clear(conversation, actor, subject, key, correlation);
    }
    @Transactional
    void clearGroup(UUID conversationId, ChatGroupAccess.Authorization authorization, UUID subject, UUID key, UUID correlation) {
        var context = groups.lock(conversationId, authorization, true);
        groups.active(context);
        clear(context.group(), context.actor().id(), subject, key, correlation);
    }
    private void clear(ChatConversationEntity conversation, UUID actor, UUID subject, UUID key, UUID correlation) {
        var normalized = conversation.id + ":" + actor;
        var retry = commands.findByActorProfileIdAndOperationAndIdempotencyKey(actor, "CLEAR_CONVERSATION", key);
        if (retry.isPresent()) {
            var command = retry.orElseThrow();
            if (!command.conversationId.equals(conversation.id)
                    || !crypto.matches(command.requestFingerprint, "CLEAR_CONVERSATION", normalized)) throw ChatException.conflict();
            return;
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        visibility.clear(conversation.id, actor, conversation.lastSequence, now);
        var digest = crypto.fingerprint("CLEAR_CONVERSATION", normalized);
        commands.save(new ChatCommandEntity(actor, "CLEAR_CONVERSATION", key, digest, "CONVERSATION", conversation.id, conversation.id, now));
        conversation.eventVersion++;
        facts.publish(conversation, "CONVERSATION_CLEARED", null, null, subject, correlation, null);
    }
}

package com.mentalbridge.community.chat;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import com.mentalbridge.community.feed.CommunityChatProfiles;
import com.mentalbridge.community.feed.CommunityChatProfiles.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatMessageTransactions {
    private final CommunityChatProfiles profiles;
    private final ChatAccess access;
    private final ChatConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ChatCommandRepository commands;
    private final ChatReadStateRepository reads;
    private final ChatCrypto crypto;
    private final ChatFacts facts;
    private final ChatUnread unread;
    private final Clock clock;
    private final ChatDeliveryCursor delivery;
    ChatMessageTransactions(CommunityChatProfiles profiles, ChatAccess access, ChatConversationRepository conversations,
            ChatMessageRepository messages, ChatCommandRepository commands, ChatReadStateRepository reads,
            ChatCrypto crypto, ChatFacts facts, ChatUnread unread, Clock clock, ChatDeliveryCursor delivery) {
        this.profiles = profiles; this.access = access; this.conversations = conversations; this.messages = messages;
        this.commands = commands; this.reads = reads; this.crypto = crypto; this.facts = facts; this.unread = unread; this.clock = clock;
        this.delivery = delivery;
    }
    @Transactional
    ChatModels.SendResult send(UUID subject, UUID conversationId, UUID peerId, UUID key, ChatModels.SendMessage input, UUID correlation) {
        var context = context(subject, conversationId, peerId);
        var actor = context.actor(); var conversation = context.conversation();
        var normalized = conversationId + ":" + input.clientMessageId() + ":" + input.content();
        var existingCommand = commands.findByActorProfileIdAndOperationAndIdempotencyKey(actor.id(), "SEND_MESSAGE", key);
        if (existingCommand.isPresent()) {
            var previous = existingCommand.orElseThrow();
            var message = messages.findById(previous.targetId).orElseThrow(ChatException::conflict);
            if (!message.conversationId.equals(conversationId) || !message.senderProfileId.equals(actor.id()) || !message.readable(clock.instant())) throw ChatException.conflict();
            if (!crypto.matches(previous.requestFingerprint, "SEND_MESSAGE", normalized)) throw ChatException.conflict();
            return saved(message, context);
        }
        var existingMessage = messages.findByConversationIdAndSenderProfileIdAndClientMessageId(conversationId, actor.id(), input.clientMessageId());
        if (existingMessage.isPresent()) {
            var message = existingMessage.orElseThrow();
            if (!message.readable(clock.instant())) throw ChatException.conflict();
            if (!crypto.matches(message.contentFingerprint, "SEND_MESSAGE", normalized)) throw ChatException.conflict();
            var result = saved(message, context);
            commands.save(new ChatCommandEntity(actor.id(), "SEND_MESSAGE", key, message.contentFingerprint, "MESSAGE", message.id, conversationId, clock.instant()));
            return result;
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (messages.countBySenderProfileIdAndCreatedAtAfter(actor.id(), now.minusSeconds(60)) >= 60
                || messages.countBySenderProfileIdAndCreatedAtAfter(actor.id(), now.minusSeconds(5)) >= 10) throw ChatException.limited();
        var messageId = UUID.randomUUID();
        conversation.lastSequence++;
        var digest = crypto.fingerprint("SEND_MESSAGE", normalized);
        var ciphertext = crypto.seal(contentPurpose(conversationId, messageId, actor.id()), input.content());
        var message = messages.saveAndFlush(new ChatMessageEntity(messageId, conversationId, actor.id(), input.clientMessageId(),
                conversation.lastSequence, ciphertext, digest, now));
        commands.save(new ChatCommandEntity(actor.id(), "SEND_MESSAGE", key, digest, "MESSAGE", messageId, conversationId, now));
        conversation.eventVersion++;
        facts.publish(conversation, "MESSAGE_SAVED", message.id, null, null, correlation, message.serverSequence);
        facts.publish(conversation, "MESSAGE_RECEIVED", message.id, actor.id(), context.peer().subject(), correlation, null);
        return saved(message, context);
    }
    @Transactional
    ChatModels.Page<ChatModels.Message> history(UUID subject, UUID conversationId, UUID peerId, String cursor, int limit) {
        var context = context(subject, conversationId, peerId);
        var purpose = historyPurpose(context);
        long after = 0; long through = context.conversation().lastSequence;
        if (cursor != null) {
            if (cursor.isBlank() || cursor.length() > 1024) throw ChatException.invalid();
            try {
                var parts = crypto.open(purpose, cursor).split("/", -1);
                if (parts.length != 2) throw ChatException.target();
                after = Long.parseLong(parts[0]); through = Long.parseLong(parts[1]);
                if (after < 0 || through < after || through > context.conversation().lastSequence) throw ChatException.target();
            } catch (RuntimeException exception) { throw ChatException.target(); }
        }
        var eligible = messages.history(conversationId, after, through, clock.instant(), limit + 1);
        var page = eligible.stream().limit(limit).toList();
        var result = page.stream().map(value -> message(value, context)).toList();
        var next = eligible.size() > limit ? crypto.seal(purpose, page.getLast().serverSequence + "/" + through) : null;
        return new ChatModels.Page<>(result, next);
    }
    @Transactional
    ChatModels.ReadResult read(UUID subject, UUID conversationId, UUID peerId, UUID messageId, UUID correlation) {
        var context = context(subject, conversationId, peerId);
        var message = messages.findById(messageId).filter(value -> value.conversationId.equals(conversationId) && value.readable(clock.instant()))
                .orElseThrow(ChatException::target);
        var state = reads.findById(new ChatReadStateEntity.ReadId(conversationId, context.actor().id()))
                .orElseGet(() -> new ChatReadStateEntity(conversationId, context.actor().id()));
        boolean advanced = state.lastReadSequence < message.serverSequence;
        state.lastReadSequence = Math.max(state.lastReadSequence, message.serverSequence);
        reads.saveAndFlush(state);
        if (advanced) {
            context.conversation().eventVersion++;
            facts.publish(context.conversation(), "READ_STATE_CHANGED", null, null, subject, correlation, null);
        }
        return unread.result(conversationId, context.actor().id());
    }
    @Transactional
    ChatModels.Message projection(UUID subject, UUID conversation, UUID peer, UUID id) {
        var context = context(subject, conversation, peer);
        return message(messages.findById(id).filter(value -> value.conversationId.equals(conversation)
                && value.readable(clock.instant())).orElseThrow(ChatException::target), context);
    }
    @Transactional
    ChatDeliveryCursor.Projection replay(UUID subject, UUID conversation, UUID peer, String cursor) {
        var context = context(subject, conversation, peer);
        var purpose = historyPurpose(context) + ":delivery";
        long after = delivery.after(purpose, cursor, 0, context.conversation().lastSequence);
        var eligible = cursor == null ? List.<ChatMessageEntity>of()
                : messages.history(conversation, after, context.conversation().lastSequence, clock.instant(), 51);
        return delivery.page(purpose, after, eligible, unread.result(conversation, context.actor().id()));
    }
    private Context context(UUID subject, UUID conversationId, UUID peerId) {
        var actor = profiles.bySubject(subject).orElseThrow(ChatException::target);
        var pair = access.pair(actor.id(), peerId);
        var conversation = conversations.findById(conversationId).orElseThrow(ChatException::target);
        if (!conversation.state.equals("ACTIVE") || !conversation.other(actor.id()).equals(peerId)) throw ChatException.target();
        return new Context(conversation, pair.stream().filter(p -> p.id().equals(actor.id())).findFirst().orElseThrow(),
                pair.stream().filter(p -> p.id().equals(peerId)).findFirst().orElseThrow());
    }
    private ChatModels.SendResult saved(ChatMessageEntity value, Context context) {
        if (!value.readable(clock.instant())) throw ChatException.conflict();
        return new ChatModels.SendResult(message(value, context), "SAVED", "UNCONFIRMED");
    }
    private ChatModels.Message message(ChatMessageEntity value, Context context) {
        var sender = value.senderProfileId.equals(context.actor().id()) ? context.actor() : context.peer();
        if (!sender.id().equals(value.senderProfileId)) throw ChatException.target();
        return new ChatModels.Message(value.id, value.conversationId, value.clientMessageId, access.peer(sender),
                crypto.open(contentPurpose(value.conversationId, value.id, value.senderProfileId), value.contentCiphertext), value.createdAt);
    }
    private String historyPurpose(Context context) {
        return context.actor().id() + ":messages:" + context.conversation().id + ":" + access.privacy(context.actor().id()).visibilityEpoch;
    }
    private String contentPurpose(UUID conversation, UUID message, UUID sender) { return "message:" + conversation + ":" + message + ":" + sender; }
    private record Context(ChatConversationEntity conversation, Profile actor, Profile peer) {}
}

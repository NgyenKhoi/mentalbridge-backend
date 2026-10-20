package com.mentalbridge.community.chat;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatGroupMessageTransactions {
    private final ChatGroupAccess access;
    private final ChatMessageRepository messages;
    private final ChatCommandRepository commands;
    private final ChatReadStateRepository reads;
    private final ChatCrypto crypto;
    private final ChatFacts facts;
    private final ChatPrivacyRepository privacy;
    private final Clock clock;
    ChatGroupMessageTransactions(ChatGroupAccess access, ChatMessageRepository messages, ChatCommandRepository commands,
            ChatReadStateRepository reads, ChatCrypto crypto, ChatFacts facts, ChatPrivacyRepository privacy, Clock clock) {
        this.access = access; this.messages = messages; this.commands = commands; this.reads = reads;
        this.crypto = crypto; this.facts = facts; this.privacy = privacy; this.clock = clock;
    }
    @Transactional
    ChatModels.SendResult send(UUID conversation, ChatGroupAccess.Authorization auth, UUID key, ChatModels.SendMessage input, UUID correlation) {
        var context = access.lock(conversation, auth, true); access.active(context);
        var normalized = conversation + ":" + input.clientMessageId() + ":" + input.content();
        var command = commands.findByActorProfileIdAndOperationAndIdempotencyKey(auth.actor(), "SEND_MESSAGE", key);
        if (command.isPresent()) {
            var previous = command.orElseThrow();
            var message = messages.findById(previous.targetId).filter(m -> m.conversationId.equals(conversation) && m.senderProfileId.equals(auth.actor())
                    && m.serverSequence > context.member().joinedAfterSequence && m.readable(clock.instant())).orElseThrow(ChatException::conflict);
            if (!crypto.matches(previous.requestFingerprint, "SEND_MESSAGE", normalized)) throw ChatException.conflict();
            return saved(message, context);
        }
        var existing = messages.findByConversationIdAndSenderProfileIdAndClientMessageId(conversation, auth.actor(), input.clientMessageId());
        if (existing.isPresent()) {
            var message = existing.orElseThrow();
            if (!message.readable(clock.instant()) || message.serverSequence <= context.member().joinedAfterSequence
                    || !crypto.matches(message.contentFingerprint, "SEND_MESSAGE", normalized)) throw ChatException.conflict();
            commands.save(new ChatCommandEntity(auth.actor(), "SEND_MESSAGE", key, message.contentFingerprint, "MESSAGE", message.id, conversation, clock.instant()));
            return saved(message, context);
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (messages.countBySenderProfileIdAndCreatedAtAfter(auth.actor(), now.minusSeconds(60)) >= 60
                || messages.countBySenderProfileIdAndCreatedAtAfter(auth.actor(), now.minusSeconds(5)) >= 10) throw ChatException.limited();
        var id = UUID.randomUUID(); context.group().lastSequence++;
        var digest = crypto.fingerprint("SEND_MESSAGE", normalized);
        var message = messages.saveAndFlush(new ChatMessageEntity(id, conversation, auth.actor(), input.clientMessageId(), context.group().lastSequence,
                crypto.seal(purpose(conversation, id, auth.actor()), input.content()), digest, now));
        commands.save(new ChatCommandEntity(auth.actor(), "SEND_MESSAGE", key, digest, "MESSAGE", id, conversation, now));
        context.group().eventVersion++;
        facts.publish(context.group(), "MESSAGE_SAVED", id, null, null, correlation, message.serverSequence);
        for (var member : context.members()) if (!member.profileId.equals(auth.actor()) && access.visible(context, member.profileId)) {
            facts.publish(context.group(), "MESSAGE_RECEIVED", id, auth.actor(), context.eligible().get(member.profileId).subject(), correlation, null);
        }
        return saved(message, context);
    }
    @Transactional
    ChatModels.Page<ChatModels.Message> history(UUID conversation, ChatGroupAccess.Authorization auth, String cursor, int limit) {
        var context = access.lock(conversation, auth, true);
        var purpose = cursorPurpose(context);
        long after = context.member().joinedAfterSequence; long through = context.group().lastSequence;
        if (cursor != null) {
            if (cursor.isBlank() || cursor.length() > 1024) throw ChatException.invalid();
            try {
                var parts = crypto.open(purpose, cursor).split("/", -1);
                if (parts.length != 2) throw ChatException.target();
                after = Long.parseLong(parts[0]); through = Long.parseLong(parts[1]);
                if (after < context.member().joinedAfterSequence || through < after || through > context.group().lastSequence) throw ChatException.target();
            } catch (RuntimeException exception) { throw ChatException.target(); }
        }
        var eligible = messages.groupHistory(conversation, visible(context), after, through, clock.instant(), limit + 1);
        var page = eligible.stream().limit(limit).toList();
        return new ChatModels.Page<>(page.stream().map(m -> message(m, context)).toList(), eligible.size() > limit
                ? crypto.seal(purpose, page.getLast().serverSequence + "/" + through) : null);
    }
    @Transactional
    ChatModels.ReadResult read(UUID conversation, ChatGroupAccess.Authorization auth, UUID id) {
        var context = access.lock(conversation, auth, true);
        var message = messages.findById(id).filter(m -> m.conversationId.equals(conversation) && m.readable(clock.instant())
                && m.serverSequence > context.member().joinedAfterSequence && visible(context).contains(m.senderProfileId)
                && context.member(m.senderProfileId).joinedAfterSequence < m.serverSequence).orElseThrow(ChatException::target);
        var read = reads.findById(new ChatReadStateEntity.ReadId(conversation, auth.actor())).orElseGet(() -> new ChatReadStateEntity(conversation, auth.actor()));
        read.lastReadSequence = Math.max(read.lastReadSequence, message.serverSequence); reads.saveAndFlush(read);
        return result(context);
    }
    ChatModels.ReadResult result(ChatGroupAccess.Context context) {
        long mark = Math.max(context.member().joinedAfterSequence, reads.findById(new ChatReadStateEntity.ReadId(context.group().id, context.actor().id()))
                .map(r -> r.lastReadSequence).orElse(0L));
        var last = messages.groupLastRead(context.group().id, visible(context), context.member().joinedAfterSequence, mark, clock.instant());
        return new ChatModels.ReadResult(last.map(m -> m.id).orElse(null), count(context, mark));
    }
    int count(ChatGroupAccess.Context context, long after) {
        return Math.toIntExact(messages.groupUnread(context.group().id, context.actor().id(), visible(context), after, clock.instant()));
    }
    @Transactional
    ChatGroupModels.Conversation conversation(UUID conversation, ChatGroupAccess.Authorization auth) {
        return conversation(access.lock(conversation, auth, true));
    }
    ChatGroupModels.Conversation conversation(ChatGroupAccess.Context context) {
        return new ChatGroupModels.Conversation(context.group().id, "GROUP", context.group().state, access.name(context.group()), context.member().role,
                context.members().stream().map(m -> access.peer(context, m.profileId)).toList(), result(context).unreadCount(), Long.toString(context.group().managementVersion));
    }
    List<UUID> visible(ChatGroupAccess.Context context) {
        return context.members().stream().map(m -> m.profileId).filter(id -> access.visible(context, id)).toList();
    }
    private ChatModels.SendResult saved(ChatMessageEntity message, ChatGroupAccess.Context context) {
        return new ChatModels.SendResult(message(message, context), "SAVED", "UNCONFIRMED");
    }
    private ChatModels.Message message(ChatMessageEntity message, ChatGroupAccess.Context context) {
        return new ChatModels.Message(message.id, message.conversationId, message.clientMessageId, access.peer(context, message.senderProfileId),
                crypto.open(purpose(message.conversationId, message.id, message.senderProfileId), message.contentCiphertext), message.createdAt);
    }
    private String cursorPurpose(ChatGroupAccess.Context context) {
        var epoch = privacy.findById(context.actor().id()).map(p -> p.visibilityEpoch).orElse(0L);
        return context.actor().id() + ":group-messages:" + context.group().id + ":" + context.member().id + ":" + epoch;
    }
    static String purpose(UUID group, UUID message, UUID sender) { return "message:" + group + ":" + message + ":" + sender; }
}

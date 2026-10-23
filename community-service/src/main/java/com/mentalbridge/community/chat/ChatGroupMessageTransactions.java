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
    private final ChatDeliveryCursor delivery;
    private final ChatHistoryVisibility visibility;
    ChatGroupMessageTransactions(ChatGroupAccess access, ChatMessageRepository messages, ChatCommandRepository commands,
            ChatReadStateRepository reads, ChatCrypto crypto, ChatFacts facts, ChatPrivacyRepository privacy, Clock clock,
            ChatDeliveryCursor delivery, ChatHistoryVisibility visibility) {
        this.access = access; this.messages = messages; this.commands = commands; this.reads = reads;
        this.crypto = crypto; this.facts = facts; this.privacy = privacy; this.clock = clock;
        this.delivery = delivery;
        this.visibility = visibility;
    }
    @Transactional
    ChatModels.SendResult send(UUID conversation, ChatGroupAccess.Authorization auth, UUID key, ChatModels.SendMessage input, UUID correlation) {
        var context = access.lock(conversation, auth, true); access.active(context);
        var normalized = conversation + ":" + input.clientMessageId() + ":" + input.content();
        var command = commands.findByActorProfileIdAndOperationAndIdempotencyKey(auth.actor(), "SEND_MESSAGE", key);
        if (command.isPresent()) {
            var previous = command.orElseThrow();
            var message = messages.findById(previous.targetId).filter(m -> m.conversationId.equals(conversation) && m.senderProfileId.equals(auth.actor())
                    && context.member().id.equals(m.senderMembershipId) && m.readable(clock.instant())).orElseThrow(ChatException::conflict);
            if (!crypto.matches(previous.requestFingerprint, "SEND_MESSAGE", normalized)) throw ChatException.conflict();
            return saved(message, context);
        }
        var existing = messages.findByConversationIdAndSenderProfileIdAndClientMessageId(conversation, auth.actor(), input.clientMessageId());
        if (existing.isPresent()) {
            var message = existing.orElseThrow();
            if (!message.readable(clock.instant()) || !context.member().id.equals(message.senderMembershipId)
                    || !crypto.matches(message.contentFingerprint, "SEND_MESSAGE", normalized)) throw ChatException.conflict();
            commands.save(new ChatCommandEntity(auth.actor(), "SEND_MESSAGE", key, message.contentFingerprint, "MESSAGE", message.id, conversation, clock.instant()));
            return saved(message, context);
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (messages.countBySenderProfileIdAndCreatedAtAfter(auth.actor(), now.minusSeconds(60)) >= 60
                || messages.countBySenderProfileIdAndCreatedAtAfter(auth.actor(), now.minusSeconds(5)) >= 10) throw ChatException.limited();
        var id = UUID.randomUUID(); context.group().lastSequence++;
        var digest = crypto.fingerprint("SEND_MESSAGE", normalized);
        var message = messages.saveAndFlush(new ChatMessageEntity(id, conversation, auth.actor(), context.member().id, input.clientMessageId(), context.group().lastSequence,
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
        long floor = Math.max(context.member().joinedAfterSequence, visibility.floor(conversation, auth.actor()));
        long after = floor; long through = context.group().lastSequence;
        if (cursor != null) {
            if (cursor.isBlank() || cursor.length() > 1024) throw ChatException.invalid();
            try {
                var parts = crypto.open(purpose, cursor).split("/", -1);
                if (parts.length != 2) throw ChatException.target();
                after = Long.parseLong(parts[0]); through = Long.parseLong(parts[1]);
                if (after < floor || through < after || through > context.group().lastSequence) throw ChatException.target();
            } catch (RuntimeException exception) { throw ChatException.target(); }
        }
        var eligible = messages.groupHistory(conversation, visible(context), after, through, clock.instant(), limit + 1);
        var page = eligible.stream().limit(limit).toList();
        return new ChatModels.Page<>(page.stream().map(m -> message(m, context)).toList(), eligible.size() > limit
                ? crypto.seal(purpose, page.getLast().serverSequence + "/" + through) : null);
    }
    @Transactional
    ChatModels.ReadResult read(UUID conversation, ChatGroupAccess.Authorization auth, UUID id, UUID correlation) {
        var context = access.lock(conversation, auth, true);
        var message = messages.findById(id).filter(m -> m.visible(clock.instant()) && access.messageVisible(context, m)
                && visibility.visible(conversation, auth.actor(), m)).orElseThrow(ChatException::target);
        var read = reads.findById(new ChatReadStateEntity.ReadId(conversation, auth.actor())).orElseGet(() -> new ChatReadStateEntity(conversation, auth.actor()));
        boolean advanced = read.lastReadSequence < message.serverSequence;
        read.lastReadSequence = Math.max(read.lastReadSequence, message.serverSequence); reads.saveAndFlush(read);
        if (advanced) {
            context.group().eventVersion++;
            facts.publish(context.group(), "READ_STATE_CHANGED", null, null, context.actor().subject(), correlation, null);
        }
        return result(context);
    }
    @Transactional
    ChatModels.Message projection(UUID conversation, ChatGroupAccess.Authorization auth, UUID id) {
        var context = access.lock(conversation, auth, true);
        return message(messages.findById(id).filter(value -> value.visible(clock.instant())
                && access.messageVisible(context, value) && visibility.visible(conversation, auth.actor(), value))
                .orElseThrow(ChatException::target), context);
    }
    @Transactional
    ChatModels.Message retract(UUID conversation, ChatGroupAccess.Authorization auth, UUID id, UUID correlation) {
        var context = access.lock(conversation, auth, true);
        var message = messages.lock(id).filter(value -> value.visible(clock.instant())
                && access.messageVisible(context, value) && value.senderProfileId.equals(auth.actor())
                && visibility.visible(conversation, auth.actor(), value))
                .orElseThrow(ChatException::target);
        if (message.state.equals("ACTIVE")) {
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            message.retract(now);
            messages.saveAndFlush(message);
            context.group().eventVersion++;
            facts.publish(context.group(), "MESSAGE_RETRACTED", message.id, null, null, correlation, null);
            for (var member : context.members()) if (!member.profileId.equals(auth.actor()) && access.visible(context, member.profileId))
                facts.publish(context.group(), "READ_STATE_CHANGED", null, null,
                        context.eligible().get(member.profileId).subject(), correlation, null);
        }
        return message(message, context);
    }
    @Transactional
    ChatDeliveryCursor.Projection replay(UUID conversation, ChatGroupAccess.Authorization auth, String cursor) {
        var context = access.lock(conversation, auth, true);
        var purpose = cursorPurpose(context) + ":delivery";
        long floor = Math.max(context.member().joinedAfterSequence, visibility.floor(conversation, auth.actor()));
        long after = delivery.after(purpose, cursor, floor, context.group().lastSequence);
        var eligible = cursor == null ? List.<ChatMessageEntity>of()
                : messages.groupHistory(conversation, visible(context), after, context.group().lastSequence, clock.instant(), 51);
        return delivery.page(purpose, after, eligible, result(context));
    }
    ChatModels.ReadResult result(ChatGroupAccess.Context context) {
        long floor = Math.max(context.member().joinedAfterSequence, visibility.floor(context.group().id, context.actor().id()));
        long mark = Math.max(floor, reads.findById(new ChatReadStateEntity.ReadId(context.group().id, context.actor().id()))
                .map(r -> r.lastReadSequence).orElse(0L));
        var last = messages.groupLastRead(context.group().id, visible(context), floor, mark, clock.instant());
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
        return context.eligible().keySet().stream().filter(id -> access.visible(context, id)).toList();
    }
    private ChatModels.SendResult saved(ChatMessageEntity message, ChatGroupAccess.Context context) {
        if (!visibility.visible(message.conversationId, context.actor().id(), message)) throw ChatException.conflict();
        return new ChatModels.SendResult(message(message, context), "SAVED", "UNCONFIRMED");
    }
    private ChatModels.Message message(ChatMessageEntity message, ChatGroupAccess.Context context) {
        var content = message.state.equals("ACTIVE")
                ? crypto.open(purpose(message.conversationId, message.id, message.senderProfileId), message.contentCiphertext) : null;
        return new ChatModels.Message(message.id, message.conversationId, message.clientMessageId, access.peer(context, message.senderProfileId),
                message.state, content, message.createdAt, message.retractedAt);
    }
    private String cursorPurpose(ChatGroupAccess.Context context) {
        var epoch = privacy.findById(context.actor().id()).map(p -> p.visibilityEpoch).orElse(0L);
        return context.actor().id() + ":group-messages:" + context.group().id + ":" + context.member().id + ":" + epoch
                + ":" + visibility.cursorScope(context.group().id, context.actor().id());
    }
    static String purpose(UUID group, UUID message, UUID sender) { return "message:" + group + ":" + message + ":" + sender; }
}

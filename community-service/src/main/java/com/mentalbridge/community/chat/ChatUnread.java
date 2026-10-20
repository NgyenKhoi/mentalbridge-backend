package com.mentalbridge.community.chat;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class ChatUnread {
    private final ChatReadStateRepository reads;
    private final ChatMessageRepository messages;
    private final Clock clock;
    ChatUnread(ChatReadStateRepository reads, ChatMessageRepository messages, Clock clock) {
        this.reads = reads; this.messages = messages; this.clock = clock;
    }
    long watermark(UUID conversation, UUID viewer) {
        return reads.findById(new ChatReadStateEntity.ReadId(conversation, viewer)).map(value -> value.lastReadSequence).orElse(0L);
    }
    int count(UUID conversation, UUID viewer) {
        return Math.toIntExact(messages.unread(conversation, viewer, watermark(conversation, viewer), clock.instant()));
    }
    ChatModels.ReadResult result(UUID conversation, UUID viewer) {
        var read = watermark(conversation, viewer);
        var reference = messages.lastRead(conversation, read, clock.instant()).map(value -> value.id).orElse(null);
        return new ChatModels.ReadResult(reference, count(conversation, viewer));
    }
}

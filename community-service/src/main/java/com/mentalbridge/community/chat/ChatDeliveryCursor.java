package com.mentalbridge.community.chat;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class ChatDeliveryCursor {
    private final ChatCrypto crypto;
    ChatDeliveryCursor(ChatCrypto crypto) { this.crypto = crypto; }
    long after(String purpose, String cursor, long floor, long tail) {
        if (cursor == null) return tail;
        if (cursor.isBlank() || cursor.length() > 1024) throw ChatException.invalid();
        try {
            long mark = Long.parseLong(crypto.open(purpose, cursor));
            if (mark < floor || mark > tail) throw ChatException.target();
            return mark;
        } catch (RuntimeException exception) { throw ChatException.target(); }
    }
    Projection page(String purpose, long after, List<ChatMessageEntity> eligible, ChatModels.ReadResult state) {
        var page = eligible.stream().limit(50).toList();
        long mark = page.isEmpty() ? after : page.getLast().serverSequence;
        return new Projection(page.stream().map(message -> message.id).toList(), crypto.seal(purpose, Long.toString(mark)), eligible.size() > 50, state);
    }
    record Projection(List<UUID> messageIds, String cursor, boolean hasMore, ChatModels.ReadResult readState) {
        @Override public String toString() { return "ChatDeliveryProjection[REDACTED]"; }
    }
}

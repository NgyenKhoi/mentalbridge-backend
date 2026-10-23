package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class ChatHistoryVisibility {
    private final ChatReadStateRepository reads;
    ChatHistoryVisibility(ChatReadStateRepository reads) { this.reads = reads; }
    ChatReadStateEntity state(UUID conversation, UUID viewer) {
        return reads.findById(new ChatReadStateEntity.ReadId(conversation, viewer))
                .orElseGet(() -> new ChatReadStateEntity(conversation, viewer));
    }
    long floor(UUID conversation, UUID viewer) { return state(conversation, viewer).clearedThroughSequence; }
    boolean visible(UUID conversation, UUID viewer, ChatMessageEntity message) {
        return message.serverSequence > floor(conversation, viewer);
    }
    String cursorScope(UUID conversation, UUID viewer) {
        var state = state(conversation, viewer);
        return state.clearedThroughSequence + ":" + (state.clearedAt == null ? "active" : state.clearedAt);
    }
    ChatReadStateEntity clear(UUID conversation, UUID viewer, long sequence, Instant now) {
        var state = state(conversation, viewer);
        state.clearedThroughSequence = Math.max(state.clearedThroughSequence, sequence);
        state.lastReadSequence = Math.max(state.lastReadSequence, state.clearedThroughSequence);
        state.clearedAt = now;
        return reads.saveAndFlush(state);
    }
}

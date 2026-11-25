package com.mentalbridge.community.chat;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatRetention {
    private final ChatMessageRepository messages;
    private final Clock clock;
    ChatRetention(ChatMessageRepository messages, Clock clock) { this.messages = messages; this.clock = clock; }
    @Transactional
    public int purgeBatch() { return messages.purgeExpired(clock.instant(), 500); }
}

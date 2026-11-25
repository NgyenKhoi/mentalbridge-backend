package com.mentalbridge.community.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "mentalbridge.community.chat", name = "enabled", havingValue = "true")
class ChatRetentionSchedule {
    private final ChatRetention retention;
    ChatRetentionSchedule(ChatRetention retention) { this.retention = retention; }
    @Scheduled(fixedDelay = 60000)
    void sweep() { for (int i = 0; i < 10 && retention.purgeBatch() == 500; i++) { } }
}

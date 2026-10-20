package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ChatRelayPropertiesTests {
    @Test
    void rejectsUnboundedSchedulingAndBatchConfiguration() {
        assertThatThrownBy(() -> new ChatRelayProperties(true, 0, Duration.ofSeconds(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatRelayProperties(true, 501, Duration.ofSeconds(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatRelayProperties(true, 100, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatRelayProperties(true, 100, Duration.ofHours(2))).isInstanceOf(IllegalArgumentException.class);
    }
}

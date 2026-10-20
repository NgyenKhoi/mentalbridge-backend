package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ChatGroupPropertiesTests {
    @Test
    void allowsOnlyAcceptedV1CapRange() {
        assertThat(new ChatGroupProperties(20).memberLimit()).isEqualTo(20);
        assertThat(new ChatGroupProperties(30).memberLimit()).isEqualTo(30);
        assertThatThrownBy(() -> new ChatGroupProperties(19)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatGroupProperties(31)).isInstanceOf(IllegalArgumentException.class);
    }
}

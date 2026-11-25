package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ChatCryptoTests {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final String firstKey = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private final String secondKey = Base64.getEncoder().encodeToString("abcdef0123456789abcdef0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    @Test
    void ciphertextUsesFreshNoncesAndBindsItsExactPurpose() {
        var crypto = new ChatCrypto(properties(firstKey, "v1", "{}"), mapper);
        var first = crypto.seal("viewer:conversation:epoch", "private synthetic text");
        var second = crypto.seal("viewer:conversation:epoch", "private synthetic text");
        assertThat(first).isNotEqualTo(second).doesNotContain("private synthetic text");
        assertThat(crypto.open("viewer:conversation:epoch", first)).isEqualTo("private synthetic text");
        assertThatThrownBy(() -> crypto.open("other:conversation:epoch", first)).isInstanceOf(ChatException.class);
        assertThatThrownBy(() -> crypto.open("viewer:conversation:new-epoch", first)).isInstanceOf(ChatException.class);
        assertThatThrownBy(() -> crypto.open("viewer:conversation:epoch", first + "x")).isInstanceOf(ChatException.class);
    }
    @Test
    void retainedKeysReadOldMessagesCursorsAndRetryDigestsAfterRotation() throws Exception {
        var old = new ChatCrypto(properties(firstKey, "v1", "{}"), mapper);
        var sealed = old.seal("message:exact-target", "synthetic old content");
        var digest = old.fingerprint("SEND_MESSAGE", "normalized input");
        var rotated = new ChatCrypto(properties(secondKey, "v2", mapper.writeValueAsString(Map.of("v1", firstKey))), mapper);
        assertThat(rotated.open("message:exact-target", sealed)).isEqualTo("synthetic old content");
        assertThat(rotated.matches(digest, "SEND_MESSAGE", "normalized input")).isTrue();
        assertThat(rotated.matches(digest, "SEND_MESSAGE", "changed input")).isFalse();
        assertThat(rotated.matches(digest, "START_DIRECT", "normalized input")).isFalse();
        assertThat(rotated.seal("new", "synthetic")).startsWith("v2.");
    }
    @Test
    void rejectsMalformedKeyRingsOrReusingAVersionWithAnotherKey() throws Exception {
        assertThatThrownBy(() -> new ChatCrypto(properties(firstKey, "v1", "{\"old\":\"short\"}"), mapper))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(firstKey);
        var collision = mapper.writeValueAsString(Map.of("v1", secondKey));
        assertThatThrownBy(() -> new ChatCrypto(properties(firstKey, "v1", collision), mapper)).isInstanceOf(IllegalArgumentException.class);
    }
    private ChatProperties properties(String key, String version, String previous) {
        return new ChatProperties(true, "", "synthetic-service-token-at-least-32-characters", Duration.ofMillis(500), Duration.ofMillis(1500), key, version, previous);
    }
}

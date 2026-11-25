package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class ChatCursors {
    private final ChatCrypto crypto;
    ChatCursors(ChatCrypto crypto) { this.crypto = crypto; }
    String encode(UUID viewer, String scope, long epoch, Instant time, UUID id) {
        return crypto.seal(viewer + ":" + scope + ":" + epoch, time + "/" + id);
    }
    Position decode(UUID viewer, String scope, long epoch, String cursor) {
        if (cursor == null) return new Position(Instant.parse("9999-12-31T23:59:59Z"), UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        if (cursor.isBlank() || cursor.length() > 1024) throw ChatException.invalid();
        try {
            var parts = crypto.open(viewer + ":" + scope + ":" + epoch, cursor).split("/", -1);
            if (parts.length != 2) throw ChatException.target();
            return new Position(Instant.parse(parts[0]), ChatModels.uuid(parts[1]));
        } catch (RuntimeException exception) { throw ChatException.target(); }
    }
    record Position(Instant time, UUID id) {}
}

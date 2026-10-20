package com.mentalbridge.community.chat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
class ChatCrypto {
    private final ChatProperties properties;
    private final Map<String, byte[]> keys = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    ChatCrypto(ChatProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        if (!properties.enabled()) return;
        try {
            Map<String, String> previous = mapper.readValue(properties.decryptionKeys(), new TypeReference<>() {});
            if (previous == null || previous.size() > 10) throw new IllegalArgumentException();
            for (var item : previous.entrySet()) {
                if (!item.getKey().matches("[A-Za-z0-9_-]{1,40}")) throw new IllegalArgumentException();
                var key = Base64.getDecoder().decode(item.getValue());
                if (key.length != 32) throw new IllegalArgumentException();
                keys.put(item.getKey(), key);
            }
            var current = Base64.getDecoder().decode(properties.encryptionKey());
            if (keys.containsKey(properties.encryptionKeyVersion())
                    && !Arrays.equals(keys.get(properties.encryptionKeyVersion()), current)) throw new IllegalArgumentException();
            keys.put(properties.encryptionKeyVersion(), current);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Community chat key ring must contain versioned 32-byte keys");
        }
    }
    String seal(String purpose, String plain) {
        properties.requireEnabled();
        try {
            var nonce = new byte[12]; random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys.get(properties.encryptionKeyVersion()), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            var encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            var joined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, joined, 0, nonce.length);
            System.arraycopy(encrypted, 0, joined, nonce.length, encrypted.length);
            return properties.encryptionKeyVersion() + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(joined);
        } catch (Exception exception) { throw ChatException.unavailable(); }
    }
    String open(String purpose, String sealed) {
        properties.requireEnabled();
        try {
            var pieces = sealed.split("\\.", -1);
            if (pieces.length != 2 || !keys.containsKey(pieces[0])) throw new IllegalArgumentException();
            var bytes = Base64.getUrlDecoder().decode(pieces[1]);
            if (bytes.length < 28) throw new IllegalArgumentException();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keys.get(pieces[0]), "AES"),
                    new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)), StandardCharsets.UTF_8);
        } catch (Exception exception) { throw ChatException.target(); }
    }
    String fingerprint(String purpose, String plain) {
        return fingerprint(properties.encryptionKeyVersion(), purpose, plain);
    }
    String fingerprint(String version, String purpose, String plain) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keys.get(version), "HmacSHA256"));
            mac.update((purpose + "\u0000").getBytes(StandardCharsets.UTF_8));
            return version + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw ChatException.unavailable(); }
    }
    boolean matches(String digest, String purpose, String plain) {
        var version = digest.split("\\.", -1)[0];
        return java.security.MessageDigest.isEqual(digest.getBytes(StandardCharsets.UTF_8),
                fingerprint(version, purpose, plain).getBytes(StandardCharsets.UTF_8));
    }
}

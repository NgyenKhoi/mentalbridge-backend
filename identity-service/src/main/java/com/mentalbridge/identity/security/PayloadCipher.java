package com.mentalbridge.identity.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.mentalbridge.identity.configuration.EncryptionProperties;

@Component
public class PayloadCipher {

	private static final int IV_BYTES = 12;
	private static final int GCM_TAG_BITS = 128;

	private final byte[] encryptionKey;
	private final byte[] fingerprintKey;
	private final String keyVersion;
	private final SecureRandom secureRandom = new SecureRandom();

	public PayloadCipher(EncryptionProperties properties) {
		this.encryptionKey = Base64.getDecoder().decode(properties.key());
		if (encryptionKey.length != 32) {
			throw new IllegalArgumentException("Identity encryption key must contain 32 bytes");
		}
		this.fingerprintKey = deriveFingerprintKey(encryptionKey);
		this.keyVersion = properties.keyVersion();
	}

	public byte[] encrypt(String plaintext) {
		try {
			var iv = new byte[IV_BYTES];
			secureRandom.nextBytes(iv);
			var cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
					new GCMParameterSpec(GCM_TAG_BITS, iv));
			var encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			var result = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, result, 0, iv.length);
			System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
			return result;
		}
		catch (Exception exception) {
			throw new IllegalStateException("Unable to encrypt idempotent response", exception);
		}
	}

	public String decrypt(byte[] ciphertext, String storedKeyVersion) {
		if (!keyVersion.equals(storedKeyVersion)) {
			throw new IllegalStateException("Idempotent response uses an unavailable encryption key version");
		}
		try {
			var iv = java.util.Arrays.copyOfRange(ciphertext, 0, IV_BYTES);
			var encrypted = java.util.Arrays.copyOfRange(ciphertext, IV_BYTES, ciphertext.length);
			var cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
					new GCMParameterSpec(GCM_TAG_BITS, iv));
			return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
		}
		catch (Exception exception) {
			throw new IllegalStateException("Unable to decrypt idempotent response", exception);
		}
	}

	public String fingerprint(String value) {
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(fingerprintKey, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("Unable to fingerprint idempotent request", exception);
		}
	}

	public String keyVersion() {
		return keyVersion;
	}

	private byte[] deriveFingerprintKey(byte[] key) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			digest.update(key);
			return digest.digest("mentalbridge-idempotency-fingerprint".getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception exception) {
			throw new IllegalStateException("Unable to derive fingerprint key", exception);
		}
	}

}

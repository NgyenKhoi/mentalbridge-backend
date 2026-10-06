package com.mentalbridge.consultation.earnings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.mentalbridge.consultation.shared.ApiException;

@Component
class PayoutDestinationCipher {

	private static final SecureRandom RANDOM = new SecureRandom();
	private final PayoutProperties properties;

	PayoutDestinationCipher(PayoutProperties properties) {
		this.properties = properties;
	}

	String encrypt(String value) {
		try {
			var iv = new byte[12];
			RANDOM.nextBytes(iv);
			var cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
			var encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
			var combined = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, combined, 0, iv.length);
			System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
			return Base64.getEncoder().encodeToString(combined);
		}
		catch (ApiException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new IllegalStateException("Unable to protect payout destination", exception);
		}
	}

	String fingerprint(String value) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("Unable to fingerprint payout destination", exception);
		}
	}

	private byte[] key() {
		try {
			var decoded = Base64.getDecoder().decode(properties.getEncryptionKey());
			if (decoded.length != 32) throw new IllegalArgumentException();
			return decoded;
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYOUT_CONFIGURATION_UNAVAILABLE",
					"Payout destination setup is not available");
		}
	}
}

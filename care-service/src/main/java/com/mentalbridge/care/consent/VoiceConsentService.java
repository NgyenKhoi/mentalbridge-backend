package com.mentalbridge.care.consent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mentalbridge.care.profile.UserProfileRepository;

@Service
public class VoiceConsentService {
	static final String CONSENT_TYPE = "AI_VOICE_PROCESSING";
	static final String VERSION = "ai-voice-processing-capstone-v1";
	static final String PURPOSE = "AI_COMPANION_VOICE";
	private final ConsentDecisionRepository decisions;
	private final UserProfileRepository profiles;
	private final VoiceConsentProperties properties;
	private final Clock clock;
	private final String disclosureText;

	public VoiceConsentService(ConsentDecisionRepository decisions, UserProfileRepository profiles,
			VoiceConsentProperties properties, Clock clock) throws java.io.IOException {
		this.decisions = decisions;
		this.profiles = profiles;
		this.properties = properties;
		this.clock = clock;
		this.disclosureText = new ClassPathResource("consent/" + VERSION + ".txt")
				.getContentAsString(StandardCharsets.UTF_8).strip();
	}

	public Disclosure disclosure() {
		return new Disclosure(CONSENT_TYPE, VERSION, "vi-VN", disclosureText, Instant.parse("2026-10-23T00:00:00Z"));
	}

	@Transactional(readOnly = true)
	public Consent current(UUID ownerId) {
		return decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(ownerId, CONSENT_TYPE)
				.filter(decision -> VERSION.equals(decision.policyVersion())).map(this::view)
				.orElseGet(() -> new Consent(CONSENT_TYPE, VERSION, false, false, null, null));
	}

	@Transactional
	public Consent record(UUID ownerId, String idempotencyKey, String version, boolean granted) {
		if (!VERSION.equals(version)) {
			throw new VoiceApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST",
					"The current voice disclosure version is required");
		}
		profiles.findByIdForUpdate(ownerId).orElseThrow(() -> new VoiceApiException(HttpStatus.UNPROCESSABLE_ENTITY,
				"INVALID_REQUEST", "A Care profile is required"));
		var storedKey = hmac("voice-consent-key|" + ownerId + "|" + idempotencyKey);
		var intentHash = hash(CONSENT_TYPE + "|" + VERSION + "|" + granted);
		var existing = decisions.findByUserIdAndConsentTypeAndIdempotencyKey(ownerId, CONSENT_TYPE, storedKey);
		if (existing.isPresent()) {
			var decision = existing.orElseThrow();
			if (!MessageDigest.isEqual(intentHash.getBytes(StandardCharsets.US_ASCII),
					decision.requestHash().getBytes(StandardCharsets.US_ASCII))) {
				throw new VoiceApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
						"Idempotency key was reused for a different voice decision");
			}
			return view(decision);
		}
		var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		var latest = decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(ownerId, CONSENT_TYPE);
		if (latest.isPresent() && !now.isAfter(latest.orElseThrow().decidedAt())) {
			now = latest.orElseThrow().decidedAt().plus(1, ChronoUnit.MICROS);
		}
		return view(decisions.saveAndFlush(new ConsentDecisionEntity(ownerId, CONSENT_TYPE, VERSION, granted,
				storedKey, intentHash, now)));
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Authorization authorize(UUID ownerId, UUID voiceSessionId) {
		var ai = granted(ownerId, AiProcessingDisclosureService.CONSENT_TYPE, AiProcessingDisclosureService.VERSION);
		var voice = granted(ownerId, CONSENT_TYPE, VERSION);
		return new Authorization(ownerId, voiceSessionId, PURPOSE, ai, voice, VERSION, ai && voice, clock.instant());
	}

	private boolean granted(UUID ownerId, String type, String version) {
		return decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(ownerId, type)
				.filter(decision -> version.equals(decision.policyVersion())).map(ConsentDecisionEntity::granted)
				.orElse(false);
	}

	private Consent view(ConsentDecisionEntity decision) {
		return new Consent(CONSENT_TYPE, VERSION, VERSION.equals(decision.policyVersion()), decision.granted(),
				decision.granted() ? decision.decidedAt() : null, decision.granted() ? null : decision.decidedAt());
	}

	private String hmac(String value) {
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(properties.idempotencyHmacKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("Voice consent fingerprint is unavailable", exception);
		}
	}

	private String hash(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	public record Disclosure(String consentType, String consentVersion, String language, String disclosureText,
			Instant publishedAt) { }

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Consent(String consentType, String consentVersion, boolean current, boolean granted,
			Instant acceptedAt, Instant revokedAt) { }

	public record Authorization(UUID ownerId, UUID voiceSessionId, String purpose, boolean aiProcessingGranted,
			boolean voiceProcessingGranted, String voiceConsentVersion, boolean authorized, Instant checkedAt) { }
}

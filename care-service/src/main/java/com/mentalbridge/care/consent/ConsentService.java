package com.mentalbridge.care.consent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;

@Service
public class ConsentService {

	private final ConsentDecisionRepository decisions;
	private final UserProfileRepository profiles;
	private final PrivacyDisclosureService disclosures;
	private final Clock clock;

	public ConsentService(ConsentDecisionRepository decisions, UserProfileRepository profiles,
			PrivacyDisclosureService disclosures, Clock clock) {
		this.decisions = decisions;
		this.profiles = profiles;
		this.disclosures = disclosures;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<DecisionView> current(UUID userId) {
		requireProfile(userId);
		return decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(userId,
				PrivacyDisclosureService.CONSENT_TYPE).map(this::view).stream().toList();
	}

	@Transactional
	public DecisionView record(UUID userId, String idempotencyKey, DecisionCommand command) {
		if (!PrivacyDisclosureService.CONSENT_TYPE.equals(command.consentType())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "CONSENT_TYPE_UNAVAILABLE",
					"This consent type is not available in Sprint 2");
		}
		disclosures.requireCurrent(command.policyVersion(), true);
		profiles.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"PROFILE_NOT_FOUND", "Care profile was not found"));
		var hash = requestHash(command);
		var existing = decisions.findByUserIdAndConsentTypeAndIdempotencyKey(userId, command.consentType(),
				idempotencyKey);
		if (existing.isPresent()) {
			var decision = existing.orElseThrow();
			if (!MessageDigest.isEqual(decision.requestHash().getBytes(StandardCharsets.US_ASCII),
					hash.getBytes(StandardCharsets.US_ASCII))) {
				throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
						"Idempotency key was reused with a different consent decision");
			}
			return view(decision);
		}
		return view(decisions.saveAndFlush(new ConsentDecisionEntity(userId, command.policyVersion(), command.granted(),
				idempotencyKey, hash, clock.instant())));
	}

	@Transactional(readOnly = true)
	public void requireGranted(UUID userId, String version) {
		disclosures.requireCurrent(version, true);
		var latest = decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(userId,
				PrivacyDisclosureService.CONSENT_TYPE).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
						"PRIVACY_DISCLOSURE_REQUIRED", "The current privacy disclosure must be accepted"));
		if (!latest.granted() || !version.equals(latest.policyVersion())) {
			throw new ApiException(HttpStatus.CONFLICT, "PRIVACY_DISCLOSURE_REQUIRED",
					"The current privacy disclosure must be accepted");
		}
	}

	private void requireProfile(UUID userId) {
		if (!profiles.existsById(userId)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Care profile was not found");
		}
	}

	private String requestHash(DecisionCommand command) {
		try {
			var value = command.consentType() + '|' + command.policyVersion() + '|' + command.granted();
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private DecisionView view(ConsentDecisionEntity value) {
		return new DecisionView(value.id(), value.consentType(), value.policyVersion(), value.granted(), value.decidedAt());
	}

	public record DecisionCommand(String consentType, String policyVersion, boolean granted) {
	}
	public record DecisionView(UUID decisionId, String consentType, String policyVersion, boolean granted,
			java.time.Instant decidedAt) {
	}
}

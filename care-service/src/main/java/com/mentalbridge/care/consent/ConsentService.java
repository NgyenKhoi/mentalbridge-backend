package com.mentalbridge.care.consent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;

@Service
public class ConsentService {

	private final ConsentDecisionRepository decisions;
	private final UserProfileRepository profiles;
	private final PrivacyDisclosureService disclosures;
	private final AiProcessingDisclosureService aiDisclosures;
	private final AiContextProcessingDisclosureService contextDisclosures;
	private final Clock clock;

	public ConsentService(ConsentDecisionRepository decisions, UserProfileRepository profiles,
			PrivacyDisclosureService disclosures, AiProcessingDisclosureService aiDisclosures,
			AiContextProcessingDisclosureService contextDisclosures, Clock clock) {
		this.decisions = decisions;
		this.profiles = profiles;
		this.disclosures = disclosures;
		this.aiDisclosures = aiDisclosures;
		this.contextDisclosures = contextDisclosures;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<DecisionView> current(UUID userId) {
		return java.util.stream.Stream.of(PrivacyDisclosureService.CONSENT_TYPE,
				AiProcessingDisclosureService.CONSENT_TYPE, AiContextProcessingDisclosureService.CONSENT_TYPE)
				.map(type -> decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(userId, type))
				.flatMap(java.util.Optional::stream).map(this::view).toList();
	}

	@Transactional
	public DecisionView record(UUID userId, String idempotencyKey, DecisionCommand command) {
		requireSupportedType(command.consentType());
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
		requireCurrentPolicy(command);
		if (AiContextProcessingDisclosureService.CONSENT_TYPE.equals(command.consentType()) && command.granted()
				&& !authorizeAiProcessing(userId).authorized()) {
			throw new ApiException(HttpStatus.CONFLICT, "AI_PROCESSING_CONSENT_REQUIRED",
					"Current AI chat consent is required");
		}
		var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		var latest = decisions.findFirstByUserIdAndConsentTypeInOrderByDecidedAtDescIdDesc(userId,
				command.consentType().equals(PrivacyDisclosureService.CONSENT_TYPE)
						? List.of(command.consentType()) : List.of(AiProcessingDisclosureService.CONSENT_TYPE,
								AiContextProcessingDisclosureService.CONSENT_TYPE));
		if (latest.isPresent() && !now.isAfter(latest.orElseThrow().decidedAt())) {
			now = latest.orElseThrow().decidedAt().plus(1, ChronoUnit.MICROS);
		}
		return view(decisions.saveAndFlush(new ConsentDecisionEntity(userId, command.consentType(), command.policyVersion(),
				command.granted(), idempotencyKey, hash, now)));
	}

	@Transactional(readOnly = true)
	public AiAuthorizationView authorizeAiProcessing(UUID userId) {
		return authorization(userId, AiProcessingDisclosureService.CONSENT_TYPE, AiProcessingDisclosureService.VERSION);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public AiConsentAuthorizations authorizeAiPurposes(UUID userId) {
		var chat = authorizeAiProcessing(userId);
		var context = authorization(userId, AiContextProcessingDisclosureService.CONSENT_TYPE,
				AiContextProcessingDisclosureService.VERSION);
		if (context.authorized() && !chat.authorized()) {
			context = new AiAuthorizationView(false, "DEPENDENCY_REQUIRED", context.consentType(),
					context.policyVersion(), context.decidedAt());
		}
		return new AiConsentAuthorizations(chat, context, clock.instant());
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public AiAuthorizationView authorizeAiContextProcessing(UUID userId) {
		return authorizeAiPurposes(userId).context();
	}

	private AiAuthorizationView authorization(UUID userId, String type, String version) {
		var latest = decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(userId,
				type);
		if (latest.isEmpty()) {
			return new AiAuthorizationView(false, "MISSING", type, null, null);
		}
		var decision = latest.orElseThrow();
		if (!version.equals(decision.policyVersion())) {
			return new AiAuthorizationView(false, "POLICY_OUTDATED", decision.consentType(), decision.policyVersion(),
					decision.decidedAt());
		}
		return new AiAuthorizationView(decision.granted(), decision.granted() ? "GRANTED" : "REVOKED",
				decision.consentType(), decision.policyVersion(), decision.decidedAt());
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

	private void requireCurrentPolicy(DecisionCommand command) {
		switch (command.consentType()) {
		case PrivacyDisclosureService.CONSENT_TYPE -> disclosures.requireCurrent(command.policyVersion(), true);
		case AiProcessingDisclosureService.CONSENT_TYPE -> aiDisclosures.requireCurrent(command.policyVersion());
		case AiContextProcessingDisclosureService.CONSENT_TYPE -> contextDisclosures.requireCurrent(command.policyVersion());
		default -> throw new ApiException(HttpStatus.BAD_REQUEST, "CONSENT_TYPE_UNAVAILABLE",
				"This consent type is not available");
		}
	}

	private void requireSupportedType(String type) {
		if (!List.of(PrivacyDisclosureService.CONSENT_TYPE, AiProcessingDisclosureService.CONSENT_TYPE,
				AiContextProcessingDisclosureService.CONSENT_TYPE).contains(type)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "CONSENT_TYPE_UNAVAILABLE", "This consent type is not available");
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
	public record AiAuthorizationView(boolean authorized, String reason, String consentType, String policyVersion,
			java.time.Instant decidedAt) {
	}
	public record AiConsentAuthorizations(AiAuthorizationView chat, AiAuthorizationView context, Instant checkedAt) {
	}
}

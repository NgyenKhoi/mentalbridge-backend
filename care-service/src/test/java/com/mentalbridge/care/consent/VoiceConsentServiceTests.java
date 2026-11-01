package com.mentalbridge.care.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.mentalbridge.care.profile.UserProfileEntity;
import com.mentalbridge.care.profile.UserProfileRepository;

class VoiceConsentServiceTests {
	private final ConsentDecisionRepository decisions = mock(ConsentDecisionRepository.class);
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);
	private final Instant now = Instant.parse("2026-10-23T10:00:00Z");
	private final UUID owner = UUID.randomUUID();
	private VoiceConsentService service;

	@BeforeEach
	void configure() throws Exception {
		service = new VoiceConsentService(decisions, profiles,
				new VoiceConsentProperties("", "synthetic-voice-hmac-at-least-32-characters"),
				Clock.fixed(now, ZoneOffset.UTC));
	}

	@Test
	void publishesTheExactApprovedImmutableVietnameseNotice() throws Exception {
		var policy = Files.readAllLines(Path.of("..", "docs", "policies", "companion-voice-policy-v1.md"));
		var lines = policy.stream().filter(line -> line.startsWith(">"))
				.map(line -> line.replaceFirst("^> ?", "").replaceAll("^\\*\\*|\\*\\*$", "")).toList();
		assertThat(service.disclosure().disclosureText()).isEqualTo(String.join("\n", lines).strip());
		assertThat(service.disclosure().consentVersion()).isEqualTo(VoiceConsentService.VERSION);
		assertThat(service.disclosure().language()).isEqualTo("vi-VN");
	}

	@Test
	void missingOrOutdatedVoiceDecisionNeverBecomesAGrant() {
		when(decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(owner, "AI_VOICE_PROCESSING"))
				.thenReturn(Optional.empty()).thenReturn(Optional.of(decision("AI_VOICE_PROCESSING", "old-voice-v0", true)));
		assertThat(service.current(owner).current()).isFalse();
		assertThat(service.current(owner).granted()).isFalse();
	}

	@Test
	void authorizesOnlyBothCurrentIndependentGrantsAndEchoesExactBinding() {
		var session = UUID.randomUUID();
		when(decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(owner, "AI_PROCESSING"))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(decision("AI_PROCESSING", AiProcessingDisclosureService.VERSION, true)))
				.thenReturn(Optional.of(decision("AI_PROCESSING", "old-ai-v0", true)))
				.thenReturn(Optional.of(decision("AI_PROCESSING", AiProcessingDisclosureService.VERSION, false)));
		when(decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(owner, "AI_VOICE_PROCESSING"))
				.thenReturn(Optional.of(decision("AI_VOICE_PROCESSING", VoiceConsentService.VERSION, true)));
		assertThat(service.authorize(owner, session).authorized()).isFalse();
		var granted = service.authorize(owner, session);
		assertThat(granted.authorized()).isTrue();
		assertThat(granted.ownerId()).isEqualTo(owner);
		assertThat(granted.voiceSessionId()).isEqualTo(session);
		assertThat(granted.purpose()).isEqualTo("AI_COMPANION_VOICE");
		assertThat(granted.checkedAt()).isEqualTo(now);
		assertThat(service.authorize(owner, session).authorized()).isFalse();
		assertThat(service.authorize(owner, session).authorized()).isFalse();
	}

	@Test
	void aTextGrantAloneCannotAuthorizeVoiceAndVoiceWithdrawalStopsIt() {
		when(decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(owner, "AI_PROCESSING"))
				.thenReturn(Optional.of(decision("AI_PROCESSING", AiProcessingDisclosureService.VERSION, true)));
		when(decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(owner, "AI_VOICE_PROCESSING"))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(decision("AI_VOICE_PROCESSING", VoiceConsentService.VERSION, false)));
		assertThat(service.authorize(owner, UUID.randomUUID()).authorized()).isFalse();
		assertThat(service.authorize(owner, UUID.randomUUID()).authorized()).isFalse();
	}

	@Test
	void recordsVoiceSeparatelyWithOwnerBoundHmacAndReplaysWithoutAnotherWrite() {
		prepareRecord();
		var key = UUID.randomUUID().toString();
		var first = service.record(owner, key, VoiceConsentService.VERSION, true);
		var saved = ArgumentCaptor.forClass(ConsentDecisionEntity.class);
		verify(decisions).saveAndFlush(saved.capture());
		var stored = ArgumentCaptor.forClass(String.class);
		verify(decisions).findByUserIdAndConsentTypeAndIdempotencyKey(eq(owner), eq("AI_VOICE_PROCESSING"), stored.capture());
		assertThat(stored.getValue()).matches("[0-9a-f]{64}").doesNotContain(key);
		assertThat(saved.getValue().consentType()).isEqualTo("AI_VOICE_PROCESSING");
		assertThat(saved.getValue().requestHash()).matches("[0-9a-f]{64}");
		assertThat(first.acceptedAt()).isEqualTo(now);
		when(decisions.findByUserIdAndConsentTypeAndIdempotencyKey(owner, "AI_VOICE_PROCESSING", stored.getValue()))
				.thenReturn(Optional.of(saved.getValue()));
		assertThat(service.record(owner, key, VoiceConsentService.VERSION, true)).isEqualTo(first);
		assertThatThrownBy(() -> service.record(owner, key, VoiceConsentService.VERSION, false))
				.isInstanceOfSatisfying(VoiceApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
		verify(decisions, times(1)).saveAndFlush(any());
	}

	@Test
	void withdrawalIsAvailableWithoutTextGrantAndCannotLoseATimestampTie() {
		prepareRecord();
		when(decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(owner, "AI_VOICE_PROCESSING"))
				.thenReturn(Optional.of(decision("AI_VOICE_PROCESSING", VoiceConsentService.VERSION, true)));
		var withdrawn = service.record(owner, UUID.randomUUID().toString(), VoiceConsentService.VERSION, false);
		assertThat(withdrawn.granted()).isFalse();
		assertThat(withdrawn.revokedAt()).isEqualTo(now.plusNanos(1000));
		assertThat(withdrawn.acceptedAt()).isNull();
	}

	@Test
	void rejectsAnotherDisclosureAndMissingProfileWithoutCreatingConsent() {
		assertThatThrownBy(() -> service.record(owner, UUID.randomUUID().toString(), "ai-processing-capstone-v2", true))
				.isInstanceOfSatisfying(VoiceApiException.class, exception -> assertThat(exception.code()).isEqualTo("INVALID_REQUEST"));
		assertThatThrownBy(() -> service.record(owner, UUID.randomUUID().toString(), VoiceConsentService.VERSION, true))
				.isInstanceOfSatisfying(VoiceApiException.class, exception -> assertThat(exception.code()).isEqualTo("INVALID_REQUEST"));
		verify(decisions, times(0)).saveAndFlush(any());
	}

	@Test
	void dedicatedServiceCredentialIsDisabledWhenEmptyAndNeverAcceptsOtherCredentials() {
		var properties = new VoiceConsentProperties("synthetic-care-service-token-at-least-32-characters", "synthetic-hmac-at-least-32-characters");
		assertThat(properties.authorizes("synthetic-care-service-token-at-least-32-characters")).isTrue();
		assertThat(properties.authorizes("synthetic-worker-token-at-least-32-characters")).isFalse();
		assertThat(properties.authorizes(null)).isFalse();
		assertThat(new VoiceConsentProperties("", "synthetic-hmac-at-least-32-characters").authorizes("")).isFalse();
		assertThat(properties.toString()).doesNotContain("synthetic");
		assertThatThrownBy(() -> properties.requireDistinctCredential(properties.journalServiceToken()))
				.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("synthetic");
		assertThatThrownBy(() -> new VoiceConsentProperties("synthetic-hmac-at-least-32-characters",
				"synthetic-hmac-at-least-32-characters").requireDistinctCredential(""))
				.isInstanceOf(IllegalArgumentException.class);
		properties.requireDistinctCredential("different-purpose-at-least-32-characters");
		new VoiceConsentProperties("", "synthetic-hmac-at-least-32-characters").requireDistinctCredential("");
		try (var validator = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
			assertThat(validator.getValidator().validate(new VoiceConsentProperties("short", "short"))).hasSize(2);
		}
	}

	private void prepareRecord() {
		when(profiles.findByIdForUpdate(owner)).thenReturn(Optional.of(mock(UserProfileEntity.class)));
		when(decisions.findByUserIdAndConsentTypeAndIdempotencyKey(eq(owner), anyString(), anyString()))
				.thenReturn(Optional.empty());
		when(decisions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private ConsentDecisionEntity decision(String type, String version, boolean granted) {
		return new ConsentDecisionEntity(owner, type, version, granted, "synthetic-consent-key", "a".repeat(64), now);
	}
}

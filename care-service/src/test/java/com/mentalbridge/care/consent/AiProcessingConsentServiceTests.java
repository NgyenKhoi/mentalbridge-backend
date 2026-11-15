package com.mentalbridge.care.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.profile.UserProfileEntity;
import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;

class AiProcessingConsentServiceTests {

	private final ConsentDecisionRepository decisions = mock(ConsentDecisionRepository.class);
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);
	private final PrivacyDisclosureService privacy = new PrivacyDisclosureService();
	private final AiProcessingDisclosureService ai = new AiProcessingDisclosureService();
	private final Instant now = Instant.parse("2026-09-16T08:00:00Z");
	private final ConsentService service = new ConsentService(decisions, profiles, privacy, ai,
			new AiContextProcessingDisclosureService(),
			Clock.fixed(now, ZoneOffset.UTC));

	@Test
	void recordsAiConsentIndependentlyWithTheCurrentPolicy() {
		var userId = UUID.randomUUID();
		when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(mock(UserProfileEntity.class)));
		when(decisions.findByUserIdAndConsentTypeAndIdempotencyKey(userId, "AI_PROCESSING",
				"ai-consent-command-001")).thenReturn(Optional.empty());
		when(decisions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		var recorded = service.record(userId, "ai-consent-command-001",
				new ConsentService.DecisionCommand("AI_PROCESSING", "ai-processing-capstone-v2", true));

		assertThat(recorded.consentType()).isEqualTo("AI_PROCESSING");
		assertThat(recorded.policyVersion()).isEqualTo("ai-processing-capstone-v2");
		assertThat(recorded.granted()).isTrue();
		assertThat(recorded.decidedAt()).isEqualTo(now);
		verify(decisions).saveAndFlush(any());
	}

	@Test
	void rejectsCrossPurposeAndUnavailableConsentPolicies() {
		var userId = UUID.randomUUID();
		when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(mock(UserProfileEntity.class)));
		assertThatThrownBy(() -> service.record(userId, "ai-consent-command-002",
				new ConsentService.DecisionCommand("AI_PROCESSING", "privacy-capstone-v3", true)))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("AI_PROCESSING_DISCLOSURE_REQUIRED"));
		assertThatThrownBy(() -> service.record(UUID.randomUUID(), "research-command-001",
				new ConsentService.DecisionCommand("RESEARCH_DATA", "research-v1", true)))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("CONSENT_TYPE_UNAVAILABLE"));
	}

	@Test
	void returnsMinimalMissingGrantedAndRevokedAuthorization() {
		var userId = UUID.randomUUID();
		when(decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(userId, "AI_PROCESSING"))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(new ConsentDecisionEntity(userId, "AI_PROCESSING",
						"ai-processing-capstone-v2", true, "ai-consent-command-003",
						"a".repeat(64), now)))
				.thenReturn(Optional.of(new ConsentDecisionEntity(userId, "AI_PROCESSING",
						"ai-processing-capstone-v2", false, "ai-consent-command-004",
						"b".repeat(64), now.plusSeconds(1))));

		assertThat(service.authorizeAiProcessing(userId).reason()).isEqualTo("MISSING");
		assertThat(service.authorizeAiProcessing(userId).reason()).isEqualTo("GRANTED");
		var revoked = service.authorizeAiProcessing(userId);
		assertThat(revoked.authorized()).isFalse();
		assertThat(revoked.reason()).isEqualTo("REVOKED");
	}

	@Test
	void rejectsAnOutdatedAiProcessingGrantUntilTheCurrentPolicyIsAccepted() {
		var userId = UUID.randomUUID();
		when(decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(userId, "AI_PROCESSING"))
				.thenReturn(Optional.of(new ConsentDecisionEntity(userId, "AI_PROCESSING",
						"ai-processing-capstone-v0", true, "legacy-ai-consent-001",
						"c".repeat(64), now.minusSeconds(1))));

		var authorization = service.authorizeAiProcessing(userId);

		assertThat(authorization.authorized()).isFalse();
		assertThat(authorization.reason()).isEqualTo("POLICY_OUTDATED");
		assertThat(authorization.policyVersion()).isEqualTo("ai-processing-capstone-v0");
	}

	@Test
	void newAccountSnapshotHasTwoMissingLevelsAndNoSyntheticDecision() {
		var snapshot = service.authorizeAiPurposes(UUID.randomUUID());
		assertThat(snapshot.chat()).isEqualTo(new ConsentService.AiAuthorizationView(false, "MISSING", "AI_PROCESSING", null, null));
		assertThat(snapshot.context()).isEqualTo(new ConsentService.AiAuthorizationView(false, "MISSING", "AI_CONTEXT_PROCESSING", null, null));
		assertThat(snapshot.checkedAt()).isEqualTo(now);
		verify(decisions, never()).saveAndFlush(any());
	}

	@Test
	void contextRequiresChatButItsOwnMissingRevokedOrOutdatedReasonWins() {
		var userId = UUID.randomUUID();
		when(decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(userId, "AI_CONTEXT_PROCESSING"))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(new ConsentDecisionEntity(userId, "AI_CONTEXT_PROCESSING",
						AiContextProcessingDisclosureService.VERSION, false, "context-refused-key", "a".repeat(64), now)))
				.thenReturn(Optional.of(new ConsentDecisionEntity(userId, "AI_CONTEXT_PROCESSING",
						"old-context-v0", true, "context-outdated-key", "a".repeat(64), now)))
				.thenReturn(Optional.of(new ConsentDecisionEntity(userId, "AI_CONTEXT_PROCESSING",
						AiContextProcessingDisclosureService.VERSION, true, "context-granted-key", "a".repeat(64), now)));
		assertThat(service.authorizeAiPurposes(userId).context().reason()).isEqualTo("MISSING");
		assertThat(service.authorizeAiPurposes(userId).context().reason()).isEqualTo("REVOKED");
		assertThat(service.authorizeAiPurposes(userId).context().reason()).isEqualTo("POLICY_OUTDATED");
		assertThat(service.authorizeAiPurposes(userId).context().reason()).isEqualTo("DEPENDENCY_REQUIRED");
	}

	@Test
	void contextGrantWithoutCurrentChatFailsAndWithdrawalRemainsAllowed() {
		var userId = UUID.randomUUID();
		when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(mock(UserProfileEntity.class)));
		when(decisions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
		assertThatThrownBy(() -> service.record(userId, "context-grant-command-001", new ConsentService.DecisionCommand(
				"AI_CONTEXT_PROCESSING", AiContextProcessingDisclosureService.VERSION, true)))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.code()).isEqualTo("AI_PROCESSING_CONSENT_REQUIRED"));
		verify(decisions, never()).saveAndFlush(any());
		assertThat(service.record(userId, "context-revoke-command-01", new ConsentService.DecisionCommand(
				"AI_CONTEXT_PROCESSING", AiContextProcessingDisclosureService.VERSION, false)).granted()).isFalse();
	}

	@Test
	void contextGrantCannotPromoteRefusedOrOutdatedChatIntoAuthority() {
		var userId = UUID.randomUUID();
		when(profiles.findByIdForUpdate(userId)).thenReturn(Optional.of(mock(UserProfileEntity.class)));
		when(decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(userId, "AI_PROCESSING"))
				.thenReturn(Optional.of(new ConsentDecisionEntity(userId, "AI_PROCESSING", AiProcessingDisclosureService.VERSION,
						false, "chat-refused-key", "a".repeat(64), now)))
				.thenReturn(Optional.of(new ConsentDecisionEntity(userId, "AI_PROCESSING", "ai-processing-capstone-v1",
						true, "chat-outdated-key", "a".repeat(64), now)));
		for (var key : java.util.List.of("context-refused-chat-key", "context-outdated-chat-key")) {
			assertThatThrownBy(() -> service.record(userId, key, new ConsentService.DecisionCommand(
					"AI_CONTEXT_PROCESSING", AiContextProcessingDisclosureService.VERSION, true)))
					.isInstanceOfSatisfying(ApiException.class,
							error -> assertThat(error.code()).isEqualTo("AI_PROCESSING_CONSENT_REQUIRED"));
		}
		verify(decisions, never()).saveAndFlush(any());
	}

	@Test
	void bothCurrentGrantsAuthorizeContextAndOutdatedChatSuspendsIt() {
		var userId = UUID.randomUUID();
		when(decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(userId, "AI_PROCESSING"))
				.thenReturn(Optional.of(new ConsentDecisionEntity(userId, "AI_PROCESSING", AiProcessingDisclosureService.VERSION,
						true, "chat-current-key", "a".repeat(64), now)))
				.thenReturn(Optional.of(new ConsentDecisionEntity(userId, "AI_PROCESSING", "ai-processing-capstone-v1",
						true, "chat-outdated-key", "a".repeat(64), now)));
		when(decisions.findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(userId, "AI_CONTEXT_PROCESSING"))
				.thenReturn(Optional.of(new ConsentDecisionEntity(userId, "AI_CONTEXT_PROCESSING", AiContextProcessingDisclosureService.VERSION,
						true, "context-current-key", "a".repeat(64), now)));
		assertThat(service.authorizeAiContextProcessing(userId).authorized()).isTrue();
		var outdated = service.authorizeAiPurposes(userId);
		assertThat(outdated.chat().reason()).isEqualTo("POLICY_OUTDATED");
		assertThat(outdated.context().reason()).isEqualTo("DEPENDENCY_REQUIRED");
	}
}

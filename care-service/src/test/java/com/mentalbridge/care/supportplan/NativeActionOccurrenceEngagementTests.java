package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.EvidenceReference;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceRepository.OccurrenceAuthorityLink;

class NativeActionOccurrenceEngagementTests {

	private static final UUID USER = UUID.randomUUID();
	private static final UUID PLAN = UUID.randomUUID();
	private static final UUID ACTION = UUID.randomUUID();
	private static final UUID OCCURRENCE = UUID.randomUUID();
	private static final UUID CORRELATION = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-10-17T08:00:00Z");
	private static final Instant COMPLETED_AT = NOW.minusSeconds(3600);
	private final SupportPlanRepository plans = mock(SupportPlanRepository.class);
	private final PlanActionRepository actions = mock(PlanActionRepository.class);
	private final SupportPlanActivityOccurrenceRepository occurrences = mock(SupportPlanActivityOccurrenceRepository.class);
	private final SupportPlanActivityOccurrenceService completion = mock(SupportPlanActivityOccurrenceService.class);
	private final SupportPlanEntity plan = mock(SupportPlanEntity.class);
	private final PlanActionEntity action = mock(PlanActionEntity.class);
	private final OccurrenceAuthorityLink link = mock(OccurrenceAuthorityLink.class);
	private final NativeActionOccurrenceService service = new NativeActionOccurrenceService(plans, actions,
			occurrences, completion, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
	private SupportPlanActivityOccurrenceEntity occurrence;

	@BeforeEach
	void ownedActiveCompletedNativeOccurrence() {
		when(link.getSupportPlanId()).thenReturn(PLAN);
		when(link.getSourceActionId()).thenReturn(ACTION);
		when(occurrences.findAuthorityLinkByIdAndUserId(OCCURRENCE, USER)).thenReturn(Optional.of(link));
		when(plans.findByIdAndUserIdForUpdate(PLAN, USER)).thenReturn(Optional.of(plan));
		when(plan.modelVersion()).thenReturn(3);
		when(plan.status()).thenReturn("ACTIVE");
		when(actions.findByIdAndUserIdForUpdate(ACTION, USER)).thenReturn(Optional.of(action));
		when(action.status()).thenReturn("ACTIVE");
		when(action.actionType()).thenReturn("EMOTION_CHECK_IN");
		occurrence = new SupportPlanActivityOccurrenceEntity();
		ReflectionTestUtils.setField(occurrence, "id", OCCURRENCE);
		ReflectionTestUtils.setField(occurrence, "supportPlanId", PLAN);
		ReflectionTestUtils.setField(occurrence, "userId", USER);
		ReflectionTestUtils.setField(occurrence, "activityScheduleId", UUID.randomUUID());
		ReflectionTestUtils.setField(occurrence, "sourceActionId", ACTION);
		ReflectionTestUtils.setField(occurrence, "sourceActionType", "EMOTION_CHECK_IN");
		ReflectionTestUtils.setField(occurrence, "sourceActionVersion", 1L);
		ReflectionTestUtils.setField(occurrence, "sourcePlanVersion", 2L);
		ReflectionTestUtils.setField(occurrence, "scheduleVersion", 1);
		ReflectionTestUtils.setField(occurrence, "localDate", LocalDate.parse("2026-10-17"));
		ReflectionTestUtils.setField(occurrence, "timezone", "UTC");
		ReflectionTestUtils.setField(occurrence, "scheduledAt", NOW.minusSeconds(7200));
		ReflectionTestUtils.setField(occurrence, "version", 3L);
		occurrence.completeWithEvidence(new EvidenceReference("JOURNAL_AI", "EMOTION_CHECK_IN",
				"owner-fact", "1", COMPLETED_AT.minusSeconds(1), COMPLETED_AT), COMPLETED_AT);
		when(occurrences.findByIdAndUserIdForUpdate(OCCURRENCE, USER)).thenReturn(Optional.of(occurrence));
		when(occurrences.saveAndFlush(occurrence)).thenReturn(occurrence);
	}

	@Test
	void helpfulnessPreservesSourceCompletionAndPublishesOnlyCodedEngagement() throws Exception {
		var result = service.replaceEngagement(USER, OCCURRENCE, 3, "COMPLETED", false,
				"HELPFUL", null, "  Private reflection  ", true, CORRELATION);

		assertThat(result.helpfulness()).isEqualTo("HELPFUL");
		assertThat(result.reflection()).isEqualTo("Private reflection");
		assertThat(result.outcomeRecordedAt()).isEqualTo(COMPLETED_AT);
		assertThat(result.completionEvidence().factId()).isEqualTo("owner-fact");
		assertThat(result.completionEvidence().verifiedAt()).isEqualTo(COMPLETED_AT);
		assertThat(result.engagementUpdatedAt()).isEqualTo(NOW);
		assertThat(occurrence.evidenceOccurredAt()).isEqualTo(COMPLETED_AT.minusSeconds(1));
		var ordered = inOrder(plans, actions, occurrences);
		ordered.verify(plans).findByIdAndUserIdForUpdate(PLAN, USER);
		ordered.verify(actions).findByIdAndUserIdForUpdate(ACTION, USER);
		ordered.verify(occurrences).findByIdAndUserIdForUpdate(OCCURRENCE, USER);
		var payload = ArgumentCaptor.forClass(String.class);
		verify(occurrences).insertActionOccurrenceOutbox(any(), eq(OCCURRENCE), eq(3L), eq(CORRELATION),
				payload.capture(), eq(NOW));
		var event = new ObjectMapper().readTree(payload.getValue());
		assertThat(event.path("changeType").asText()).isEqualTo("ENGAGEMENT_CHANGED");
		assertThat(event.at("/engagement/helpfulness").asText()).isEqualTo("HELPFUL");
		assertThat(event.at("/ownerFact/factId").asText()).isEqualTo("owner-fact");
		assertThat(event.at("/orchestration/outcomeRecordedAt").asText()).isEqualTo(COMPLETED_AT.toString());
		assertThat(payload.getValue()).doesNotContain("Private reflection", "reflection", "sourceSlotId", "sourceContentVersion");
		verifyNoInteractions(completion);
	}

	@Test
	void naturalReplayAcceptsTheSameMetadataWithAStaleVersion() {
		occurrence.replaceNativeEngagement(false, "HELPFUL", null, null, false, NOW.minusSeconds(1));

		var result = service.replaceEngagement(USER, OCCURRENCE, 0, "COMPLETED", false,
				"HELPFUL", null, null, false, CORRELATION);

		assertThat(result.engagementUpdatedAt()).isEqualTo(NOW.minusSeconds(1));
		verify(occurrences, never()).saveAndFlush(any());
		verifyNoEvent();
	}

	@Test
	void identicalEngagementReplayAfterPausingReturnsTheUnchangedOccurrence() {
		occurrence.replaceNativeEngagement(false, "HELPFUL", null, "Private", true, NOW.minusSeconds(1));
		when(plan.status()).thenReturn("PAUSED");

		var result = service.replaceEngagement(USER, OCCURRENCE, 0, "COMPLETED", false,
				"HELPFUL", null, "  Private  ", true, CORRELATION);

		assertThat(result.occurrenceId()).isEqualTo(OCCURRENCE);
		assertThat(result.version()).isEqualTo(3);
		assertThat(result.helpfulness()).isEqualTo("HELPFUL");
		assertThat(result.reflection()).isEqualTo("Private");
		assertThat(result.summaryReuseApproved()).isTrue();
		assertThat(result.engagementUpdatedAt()).isEqualTo(NOW.minusSeconds(1));
		assertThat(result.outcomeRecordedAt()).isEqualTo(COMPLETED_AT);
		assertThat(result.completionEvidence().factId()).isEqualTo("owner-fact");
		verify(occurrences, never()).saveAndFlush(any());
		verifyNoEvent();
		verifyNoInteractions(completion);
	}

	@Test
	void staleDifferentWriteReturns412WithoutMutation() {
		assertThatThrownBy(() -> service.replaceEngagement(USER, OCCURRENCE, 2, "COMPLETED", false,
				"HELPFUL", null, null, false, CORRELATION))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.status()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
					assertThat(exception.code()).isEqualTo("OCCURRENCE_VERSION_MISMATCH");
				});
		assertThat(occurrence.helpfulness()).isNull();
		verifyNoEvent();
	}

	@Test
	void engagementCannotManufactureSourceCompletionOrReopenAcceptedEvidence() {
		assertThatThrownBy(() -> service.replaceEngagement(USER, OCCURRENCE, 3, "SCHEDULED", false,
				null, null, null, false, CORRELATION))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.code()).isEqualTo("AUTHORITATIVE_COMPLETION_REQUIRED"));
		ReflectionTestUtils.setField(occurrence, "state", "SCHEDULED");
		assertThatThrownBy(() -> service.replaceEngagement(USER, OCCURRENCE, 3, "COMPLETED", false,
				"HELPFUL", null, null, false, CORRELATION))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.code()).isEqualTo("AUTHORITATIVE_COMPLETION_REQUIRED"));
		verifyNoInteractions(completion);
		verifyNoEvent();
	}

	@Test
	void deleteOnlyClearsMetadataAndReplaysWithoutASecondWrite() {
		occurrence.replaceNativeEngagement(true, "VERY_HELPFUL", null, "Private", true, NOW.minusSeconds(1));

		var result = service.deleteEngagement(USER, OCCURRENCE, 3, CORRELATION);
		when(plan.status()).thenReturn("PAUSED");
		service.deleteEngagement(USER, OCCURRENCE, 0, CORRELATION);

		assertThat(result.state()).isEqualTo("COMPLETED");
		assertThat(result.completionEvidence().factId()).isEqualTo("owner-fact");
		assertThat(result.outcomeRecordedAt()).isEqualTo(COMPLETED_AT);
		assertThat(result.hidden()).isFalse();
		assertThat(result.helpfulness()).isNull();
		assertThat(result.reflection()).isNull();
		assertThat(result.summaryReuseApproved()).isFalse();
		verify(occurrences).saveAndFlush(occurrence);
	}

	@Test
	void existingCompletedOccurrenceStateReplayKeepsTheOriginalWireShape() {
		var result = service.change(USER, OCCURRENCE, 3, "COMPLETED", CORRELATION);
		var json = new ObjectMapper().findAndRegisterModules().valueToTree(result);

		assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder("occurrenceId", "actionId", "actionType",
				"actionVersion", "scheduleId", "scheduleVersion", "representationMode", "version", "localDate",
				"scheduledAt", "state", "displayState", "stateReason", "completionEvidence", "outcomeRecordedAt");
		assertThat(result.engagementUpdatedAt()).isNull();
		assertThat(result.completionEvidence().factId()).isEqualTo("owner-fact");
		verify(occurrences, never()).saveAndFlush(any());
		verifyNoEvent();
	}

	@Test
	void existingStateOnlyTimestampDoesNotIntroduceEngagementOnStateReplay() {
		ReflectionTestUtils.setField(occurrence, "engagementUpdatedAt", NOW.minusSeconds(30));

		var result = service.change(USER, OCCURRENCE, 3, "COMPLETED", CORRELATION);
		var json = new ObjectMapper().findAndRegisterModules().valueToTree(result);

		assertThat(json.fieldNames()).toIterable().doesNotContain("hidden", "helpfulness", "barrierCode",
				"reflection", "summaryReuseApproved", "engagementUpdatedAt");
		assertThat(result.completionEvidence().factId()).isEqualTo("owner-fact");
		assertThat(occurrence.engagementUpdatedAt()).isEqualTo(NOW.minusSeconds(30));
		verify(occurrences, never()).saveAndFlush(any());
		verifyNoEvent();
	}

	@Test
	void deletingAbsentMetadataReturnsClearedFlagsWithoutChangingCompletion() {
		var result = service.deleteEngagement(USER, OCCURRENCE, 0, CORRELATION);
		var json = new ObjectMapper().findAndRegisterModules().valueToTree(result);

		assertThat(result.state()).isEqualTo("COMPLETED");
		assertThat(result.outcomeRecordedAt()).isEqualTo(COMPLETED_AT);
		assertThat(result.completionEvidence().factId()).isEqualTo("owner-fact");
		assertThat(json.get("hidden").asBoolean()).isFalse();
		assertThat(json.get("summaryReuseApproved").asBoolean()).isFalse();
		assertThat(json.fieldNames()).toIterable().doesNotContain("helpfulness", "barrierCode", "reflection", "engagementUpdatedAt");
		verify(occurrences, never()).saveAndFlush(any());
		verifyNoEvent();
	}

	@Test
	void bareSkipAndReopenKeepEngagementAbsentOnTheWire() {
		ReflectionTestUtils.setField(occurrence, "state", "SCHEDULED");
		ReflectionTestUtils.setField(occurrence, "completedAt", null);
		ReflectionTestUtils.setField(occurrence, "evidenceId", null);

		var skipped = service.change(USER, OCCURRENCE, 3, "SKIPPED", CORRELATION);
		var reopened = service.change(USER, OCCURRENCE, 3, "SCHEDULED", CORRELATION);

		assertThat(skipped.state()).isEqualTo("SKIPPED");
		assertThat(reopened.state()).isEqualTo("SCHEDULED");
		for (var result : java.util.List.of(skipped, reopened)) {
			var json = new ObjectMapper().findAndRegisterModules().valueToTree(result);
			assertThat(result.engagementUpdatedAt()).isNull();
			assertThat(json.fieldNames()).toIterable().doesNotContain("hidden", "helpfulness", "barrierCode",
					"reflection", "summaryReuseApproved", "engagementUpdatedAt");
		}
	}

	@Test
	void skippedBarrierKeepsItsOutcomeAndReopeningClearsEngagement() {
		ReflectionTestUtils.setField(occurrence, "state", "SKIPPED");
		ReflectionTestUtils.setField(occurrence, "skippedAt", COMPLETED_AT);
		ReflectionTestUtils.setField(occurrence, "completedAt", null);
		ReflectionTestUtils.setField(occurrence, "evidenceId", null);

		var result = service.replaceEngagement(USER, OCCURRENCE, 3, "SKIPPED", false,
				null, "LOW_ENERGY", "Private", true, CORRELATION);

		assertThat(result.outcomeRecordedAt()).isEqualTo(COMPLETED_AT);
		assertThat(result.barrierCode()).isEqualTo("LOW_ENERGY");
		service.change(USER, OCCURRENCE, 3, "SCHEDULED", CORRELATION);
		assertThat(occurrence.barrierCode()).isNull();
		assertThat(occurrence.reflection()).isNull();
		assertThat(occurrence.summaryReuseApproved()).isFalse();
	}

	@Test
	void rejectsPausedAndTerminalPlansAndEndedActions() {
		occurrence.replaceNativeEngagement(false, "HELPFUL", null, null, false, NOW.minusSeconds(1));
		for (var status : java.util.List.of("PAUSED", "COMPLETED", "SUPERSEDED", "DISCARDED")) {
			when(plan.status()).thenReturn(status);
			assertThatThrownBy(() -> service.deleteEngagement(USER, OCCURRENCE, 3, CORRELATION))
					.isInstanceOfSatisfying(ApiException.class, exception ->
							assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_NOT_ACTIVE"));
		}
		when(plan.status()).thenReturn("ACTIVE");
		when(action.status()).thenReturn("ENDED");
		assertThatThrownBy(() -> service.deleteEngagement(USER, OCCURRENCE, 3, CORRELATION))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_NOT_ACTIVE"));
		verifyNoEvent();
	}

	@Test
	void foreignOrMissingOccurrenceIsNotFoundWithoutLookingUpPlan() {
		when(occurrences.findAuthorityLinkByIdAndUserId(OCCURRENCE, USER)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.deleteEngagement(USER, OCCURRENCE, 3, CORRELATION))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
					assertThat(exception.code()).isEqualTo("OCCURRENCE_NOT_FOUND");
				});
		verifyNoInteractions(plans, actions, completion);
	}

	@Test
	void rejectsLegacyAndCancelledOccurrences() {
		when(plan.modelVersion()).thenReturn(2);
		assertThatThrownBy(() -> service.deleteEngagement(USER, OCCURRENCE, 3, CORRELATION))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.code()).isEqualTo("SUPPORT_PLAN_MODEL_VERSION_UNSUPPORTED"));
		when(plan.modelVersion()).thenReturn(3);
		occurrence.replaceNativeEngagement(true, null, null, null, false, NOW.minusSeconds(1));
		ReflectionTestUtils.setField(occurrence, "state", "CANCELLED");
		assertThatThrownBy(() -> service.deleteEngagement(USER, OCCURRENCE, 3, CORRELATION))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.code()).isEqualTo("OCCURRENCE_NOT_OPEN"));
		verifyNoEvent();
	}

	@Test
	void validatesHelpfulnessAndReflectionBeforePersistence() {
		assertThatThrownBy(() -> service.replaceEngagement(USER, OCCURRENCE, 3, "COMPLETED", false,
				"UNSUPPORTED", null, null, false, CORRELATION)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> service.replaceEngagement(USER, OCCURRENCE, 3, "COMPLETED", false,
				"HELPFUL", null, " ", false, CORRELATION)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> service.replaceEngagement(USER, OCCURRENCE, 3, "SKIPPED", false,
				"HELPFUL", null, null, false, CORRELATION)).isInstanceOf(ApiException.class);
		verifyNoInteractions(plans, actions, occurrences, completion);
	}

	private void verifyNoEvent() {
		verify(occurrences, never()).insertActionOccurrenceOutbox(any(), any(), anyLong(), any(), anyString(), any());
	}
}

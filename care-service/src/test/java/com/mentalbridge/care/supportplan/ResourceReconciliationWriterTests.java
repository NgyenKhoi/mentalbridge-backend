package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.ResourceEvidenceHttpClient.Evidence;

class ResourceReconciliationWriterTests {
	private static final Instant NOW = Instant.parse("2026-10-21T15:15:00Z");
	private final UUID userId = UUID.randomUUID();
	private final UUID planId = UUID.randomUUID();
	private final UUID resourceId = UUID.randomUUID();
	private final SupportPlanRepository plans = mock(SupportPlanRepository.class);
	private final PlanActionRepository actions = mock(PlanActionRepository.class);
	private final SupportPlanActivityOccurrenceRepository occurrences = mock(SupportPlanActivityOccurrenceRepository.class);
	private final SupportPlanActivityOccurrenceService completion = mock(SupportPlanActivityOccurrenceService.class);
	private final NativeActionOccurrenceService views = mock(NativeActionOccurrenceService.class);
	private final ResourceEvidenceClient evidence = mock(ResourceEvidenceClient.class);
	private final ResourceReconciliationWriter writer = new ResourceReconciliationWriter(plans, actions, occurrences,
			completion, views, Clock.fixed(NOW, ZoneOffset.UTC));
	private final ResourceReconciliationService service = new ResourceReconciliationService(writer, evidence);

	@Test
	void twoScheduleZonesNearMidnightDriveContentDateAndFactAcceptanceIndependently() {
		UUID losAngeles = target("America/Los_Angeles", "2026-10-21");
		UUID tokyo = target("Asia/Tokyo", "2026-10-22");
		when(evidence.read(any(), anyString(), any(), anyString(), anyList(), anyString(), any())).thenAnswer(invocation -> {
			String zone = invocation.getArgument(3);
			assertThat(invocation.getArgument(2, LocalDate.class)).isEqualTo(
					LocalDate.parse(zone.equals("Asia/Tokyo") ? "2026-10-22" : "2026-10-21"));
			return new Evidence("RESOURCE_PRACTICE_SESSION", UUID.randomUUID().toString(), "7", NOW.minusSeconds(60), NOW);
		});

		service.reconcile(userId, losAngeles, 0, "token", UUID.randomUUID());
		service.reconcile(userId, tokyo, 0, "token", UUID.randomUUID());

		org.mockito.Mockito.verify(completion, org.mockito.Mockito.times(2)).completeFromOwnerFact(
				org.mockito.ArgumentMatchers.eq(userId), any(), any(), any());
		org.mockito.Mockito.verifyNoMoreInteractions(completion);
	}

	@Test
	void futureDateIsRejectedInTheOccurrenceZoneBeforeContent() {
		UUID future = target("America/Los_Angeles", "2026-10-22");
		assertThatThrownBy(() -> service.reconcile(userId, future, 0, "token", UUID.randomUUID()))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.code()).isEqualTo("OCCURRENCE_NOT_OPEN"));
		verifyNoInteractions(evidence, completion);
	}

	private UUID target(String zone, String date) {
		var plan = mock(SupportPlanEntity.class);
		when(plan.id()).thenReturn(planId);
		when(plan.modelVersion()).thenReturn(3);
		when(plan.status()).thenReturn("ACTIVE");
		when(plan.activatedAt()).thenReturn(NOW.minusSeconds(3600));
		when(plans.findByIdAndUserId(planId, userId)).thenReturn(Optional.of(plan));
		when(plans.findByIdAndUserIdForUpdate(planId, userId)).thenReturn(Optional.of(plan));
		UUID actionId = UUID.randomUUID();
		var action = mock(PlanActionEntity.class);
		when(action.id()).thenReturn(actionId);
		when(action.userId()).thenReturn(userId);
		when(action.supportPlanId()).thenReturn(planId);
		when(action.actionType()).thenReturn("RESOURCE");
		when(action.status()).thenReturn("ACTIVE");
		when(action.activatedAt()).thenReturn(NOW.minusSeconds(3600));
		when(action.sourceOwner()).thenReturn("CONTENT");
		when(action.sourceKind()).thenReturn("RESOURCE_VERSION");
		when(action.sourceId()).thenReturn(resourceId.toString());
		when(action.sourceRevision()).thenReturn("7");
		when(actions.findById(actionId)).thenReturn(Optional.of(action));
		when(actions.findByIdAndUserIdForUpdate(actionId, userId)).thenReturn(Optional.of(action));
		UUID id = UUID.randomUUID();
		var occurrence = mock(SupportPlanActivityOccurrenceEntity.class);
		when(occurrence.id()).thenReturn(id);
		when(occurrence.supportPlanId()).thenReturn(planId);
		when(occurrence.sourceActionId()).thenReturn(actionId);
		when(occurrence.sourceActionType()).thenReturn("RESOURCE");
		when(occurrence.localDate()).thenReturn(LocalDate.parse(date));
		when(occurrence.timezone()).thenReturn(zone);
		when(occurrence.state()).thenReturn("SCHEDULED");
		when(occurrences.findByIdAndUserId(id, userId)).thenReturn(Optional.of(occurrence));
		when(occurrences.findByIdAndUserIdForUpdate(id, userId)).thenReturn(Optional.of(occurrence));
		when(occurrences.findUsedResourceFactIds(userId, resourceId.toString(), "7", id)).thenReturn(List.of());
		return id;
	}
}

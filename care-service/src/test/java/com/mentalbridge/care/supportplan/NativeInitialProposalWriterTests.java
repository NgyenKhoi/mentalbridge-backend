package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.profile.UserProfileEntity;
import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;

class NativeInitialProposalWriterTests {
	private final UUID user = UUID.randomUUID();
	private final UUID planId = UUID.randomUUID();
	private final Instant now = Instant.parse("2026-10-13T01:00:00Z");
	private final SupportPlanRepository plans = mock(SupportPlanRepository.class);
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);
	private final NativeSupportPlanWriter writer = new NativeSupportPlanWriter(plans,
			mock(SupportPlanTemplateFamilyRepository.class), mock(SupportPlanFocusRepository.class),
			mock(PlanActionRepository.class), profiles, mock(SupportPlanActivityOccurrenceService.class),
			mock(SupportPlanScheduleCommandRepository.class), mock(SupportPlanScheduleService.class), mock(ObjectMapper.class));

	@BeforeEach
	void setup() {
		when(profiles.findByIdForUpdate(user)).thenReturn(Optional.of(mock(UserProfileEntity.class)));
	}

	@Test
	void currentPlanTakesPriorityAndIsRecorded() {
		var current = mock(SupportPlanEntity.class);
		when(current.id()).thenReturn(planId);
		when(current.status()).thenReturn("ACTIVE");
		when(current.version()).thenReturn(3L);
		when(plans.findByUserIdAndStatusIn(user, List.of("ACTIVE", "PAUSED"))).thenReturn(Optional.of(current));
		var state = writer.initialState(user, "initial-test-key-0001", "hash", now);
		assertThat(state.id()).isEqualTo(planId);
		verify(plans).insertRequest(user, "initial-test-key-0001", "hash", planId, now);
		verify(plans, never()).findByUserIdAndStatus(user, "DRAFT");
	}

	@Test
	void changedDraftIsRejectedAfterExternalValidation() {
		var draft = mock(SupportPlanEntity.class);
		when(draft.id()).thenReturn(planId);
		when(draft.version()).thenReturn(2L);
		when(plans.findByUserIdAndStatus(user, "DRAFT")).thenReturn(Optional.of(draft));
		assertThatThrownBy(() -> writer.acceptInitialDraft(user, planId, 1, "initial-test-key-0001", "hash", now, () -> { }))
				.isInstanceOf(ApiException.class);
		verify(plans, never()).insertRequest(any(), anyString(), anyString(), any(), any());
	}
}

package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.EvidenceReference;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceRepository.OccurrenceAuthorityLink;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;

class NativeActionOccurrenceEngagementEventContractTests {

	@Test
	void realEngagementChangedPayloadConformsToActionOccurrenceV2Schema() throws Exception {
		var userId = UUID.randomUUID();
		var planId = UUID.randomUUID();
		var actionId = UUID.randomUUID();
		var occurrenceId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var now = Instant.parse("2026-10-17T08:00:00Z");
		var mapper = new ObjectMapper();
		var plans = mock(SupportPlanRepository.class);
		var actions = mock(PlanActionRepository.class);
		var occurrences = mock(SupportPlanActivityOccurrenceRepository.class);
		var plan = mock(SupportPlanEntity.class);
		var action = mock(PlanActionEntity.class);
		var link = mock(OccurrenceAuthorityLink.class);
		when(link.getSupportPlanId()).thenReturn(planId);
		when(link.getSourceActionId()).thenReturn(actionId);
		when(occurrences.findAuthorityLinkByIdAndUserId(occurrenceId, userId)).thenReturn(Optional.of(link));
		when(plans.findByIdAndUserIdForUpdate(planId, userId)).thenReturn(Optional.of(plan));
		when(plan.modelVersion()).thenReturn(3);
		when(plan.status()).thenReturn("ACTIVE");
		when(actions.findByIdAndUserIdForUpdate(actionId, userId)).thenReturn(Optional.of(action));
		when(action.status()).thenReturn("ACTIVE");
		var occurrence = new SupportPlanActivityOccurrenceEntity();
		ReflectionTestUtils.setField(occurrence, "id", occurrenceId);
		ReflectionTestUtils.setField(occurrence, "supportPlanId", planId);
		ReflectionTestUtils.setField(occurrence, "userId", userId);
		ReflectionTestUtils.setField(occurrence, "activityScheduleId", UUID.randomUUID());
		ReflectionTestUtils.setField(occurrence, "sourceActionId", actionId);
		ReflectionTestUtils.setField(occurrence, "sourceActionType", "EMOTION_CHECK_IN");
		ReflectionTestUtils.setField(occurrence, "sourceActionVersion", 1L);
		ReflectionTestUtils.setField(occurrence, "sourcePlanVersion", 2L);
		ReflectionTestUtils.setField(occurrence, "scheduleVersion", 1);
		ReflectionTestUtils.setField(occurrence, "localDate", LocalDate.parse("2026-10-17"));
		ReflectionTestUtils.setField(occurrence, "scheduledAt", now.minusSeconds(7200));
		ReflectionTestUtils.setField(occurrence, "version", 3L);
		occurrence.completeWithEvidence(new EvidenceReference("JOURNAL_AI", "EMOTION_CHECK_IN",
				"owner-fact", "1", now.minusSeconds(3601), now.minusSeconds(3600)), now.minusSeconds(3600));
		when(occurrences.findByIdAndUserIdForUpdate(occurrenceId, userId)).thenReturn(Optional.of(occurrence));
		when(occurrences.saveAndFlush(occurrence)).thenReturn(occurrence);
		var service = new NativeActionOccurrenceService(plans, actions, occurrences,
				mock(SupportPlanActivityOccurrenceService.class), Clock.fixed(now, ZoneOffset.UTC), mapper);

		service.replaceEngagement(userId, occurrenceId, 3, "COMPLETED", false,
				"HELPFUL", null, "Private reflection", true, correlationId);

		var capturedPayload = ArgumentCaptor.forClass(String.class);
		verify(occurrences).insertActionOccurrenceOutbox(any(), eq(occurrenceId), eq(3L), eq(correlationId),
				capturedPayload.capture(), eq(now));
		var payload = mapper.readTree(capturedPayload.getValue());
		var eventSchema = mapper.readTree(Path.of("..", "contracts", "events", "care",
				"support-plan-action-occurrence-changed-v2.schema.json").toFile());
		ObjectNode payloadSchema = eventSchema.at("/properties/payload").deepCopy();
		payloadSchema.set("$defs", eventSchema.path("$defs"));
		var validator = SchemaRegistry.withDialect(Dialects.getDraft202012())
				.getSchema(payloadSchema.toString(), InputFormat.JSON);

		assertThat(payload.path("changeType").asText()).isEqualTo("ENGAGEMENT_CHANGED");
		assertThat(validator.validate(payload, context -> context.executionConfig(
				config -> config.formatAssertionsEnabled(true)))).isEmpty();
		assertThat(capturedPayload.getValue()).doesNotContain("Private reflection", "reflection");
	}
}

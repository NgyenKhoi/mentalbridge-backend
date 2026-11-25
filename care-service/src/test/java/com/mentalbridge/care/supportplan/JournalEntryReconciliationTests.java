package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.JournalEntryEvidenceHttpClient.Evidence;
import com.mentalbridge.care.supportplan.JournalEntryEvidenceHttpClient.Fact;
import com.mentalbridge.care.supportplan.PlanActionEntity.Presentation;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.EvidenceReference;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.Provenance;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.SourceReference;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.ActionOccurrenceCompletion;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.AuthoritativeOwnerFact;

class JournalEntryReconciliationTests {
	private static final UUID USER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID PLAN = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID ACTION = UUID.fromString("30000000-0000-4000-8000-000000000001");
	private static final UUID OCCURRENCE = UUID.fromString("40000000-0000-4000-8000-000000000001");
	private static final UUID FACT = UUID.fromString("50000000-0000-4000-8000-000000000001");
	private static final UUID CORRELATION = UUID.fromString("60000000-0000-4000-8000-000000000001");
	private static final Instant NOW = Instant.parse("2026-10-13T08:00:00Z");
	private static final LocalDate DATE = LocalDate.parse("2026-10-13");
	private final SupportPlanRepository plans = mock(SupportPlanRepository.class);
	private final PlanActionRepository actions = mock(PlanActionRepository.class);
	private final SupportPlanActivityOccurrenceRepository occurrences = mock(SupportPlanActivityOccurrenceRepository.class);
	private final SupportPlanActivityOccurrenceService completion = mock(SupportPlanActivityOccurrenceService.class);
	private final JournalEntryReconciliationWriter writer = new JournalEntryReconciliationWriter(
			plans, actions, occurrences, completion, Clock.fixed(NOW, ZoneOffset.UTC));
	private SupportPlanEntity plan;
	private PlanActionEntity action;
	private SupportPlanActivityOccurrenceEntity occurrence;

	@BeforeEach
	void prepareOwnedActiveNativeAction() {
		plan = SupportPlanEntity.nativeDraft(PLAN, USER, UUID.randomUUID(), "routing-v2", NOW.minusSeconds(7200),
				"support-plan-v3-actions-v1", "PLUS", "DEMO", "service-entitlement-v1", 1,
				NOW.minusSeconds(7200), "DOMAIN_AWARE_WELLBEING_SUPPORT", "Plan rationale",
				"NEGATIVE_SAFETY_SCREEN", "NO_SAFETY_SIGNAL", "safety-v1", "STANDARD_SAFETY_REMINDER",
				"Safety guidance", 1, NOW.minusSeconds(7200));
		plan.activate(NOW.minusSeconds(3600));
		action = new PlanActionEntity(ACTION, PLAN, UUID.randomUUID(), USER, 1, "JOURNAL",
				"NOTICE_EMOTIONS", null, new SourceReference("JOURNAL_AI", "JOURNAL_TEMPLATE",
						"short-reflection", "1", "journal-writing-intent-v1", null, null, NOW),
				new Provenance("USER_SELECTED", null, null, NOW.minusSeconds(7200)),
				new Presentation("Emotion check-in", null, null, null), NOW.minusSeconds(7200));
		action.activate(NOW.minusSeconds(3600));
		occurrence = new SupportPlanActivityOccurrenceEntity();
		ReflectionTestUtils.setField(occurrence, "id", OCCURRENCE);
		ReflectionTestUtils.setField(occurrence, "supportPlanId", PLAN);
		ReflectionTestUtils.setField(occurrence, "userId", USER);
		ReflectionTestUtils.setField(occurrence, "sourceActionId", ACTION);
		ReflectionTestUtils.setField(occurrence, "sourceActionType", "JOURNAL");
		ReflectionTestUtils.setField(occurrence, "sourceActionVersion", 0L);
		ReflectionTestUtils.setField(occurrence, "localDate", DATE);
		ReflectionTestUtils.setField(occurrence, "timezone", "Asia/Ho_Chi_Minh");
		ReflectionTestUtils.setField(occurrence, "state", "SCHEDULED");
		when(occurrences.findByIdAndUserId(OCCURRENCE, USER)).thenReturn(Optional.of(occurrence));
		when(occurrences.findByIdAndUserIdForUpdate(OCCURRENCE, USER)).thenReturn(Optional.of(occurrence));
		when(plans.findByIdAndUserId(PLAN, USER)).thenReturn(Optional.of(plan));
		when(plans.findByIdAndUserIdForUpdate(PLAN, USER)).thenReturn(Optional.of(plan));
		when(actions.findById(ACTION)).thenReturn(Optional.of(action));
		when(actions.findByIdAndUserIdForUpdate(ACTION, USER)).thenReturn(Optional.of(action));
	}

	@Test
	void resolvesDateAndSourceFromCareAndAcceptsOnlyTheOwnerFactAfterLocks() {
		var context = writer.context(USER, OCCURRENCE);
		when(completion.completeFromOwnerFact(eq(USER), eq(OCCURRENCE), any(), eq(CORRELATION)))
				.thenAnswer(invocation -> {
					AuthoritativeOwnerFact value = invocation.getArgument(2);
					assertThat(value.subjectUserId()).isEqualTo(USER);
					assertThat(value.linkedSource()).isEqualTo(context.source());
					assertThat(value.fact().factId()).isEqualTo(FACT.toString());
					return new ActionOccurrenceCompletion(OCCURRENCE, 1, "COMPLETED", NOW, value.fact());
				});

		var result = writer.accept(USER, context, FACT, recorded(), CORRELATION);

		assertThat(result.occurrenceState()).isEqualTo("COMPLETED");
		assertThat(result.sourceState()).isEqualTo("RECORDED");
		assertThat(result.localDate()).isEqualTo(DATE);
		assertThat(result.version()).isEqualTo(1);
		var ordered = inOrder(plans, actions, occurrences, completion);
		ordered.verify(plans).findByIdAndUserIdForUpdate(PLAN, USER);
		ordered.verify(actions).findByIdAndUserIdForUpdate(ACTION, USER);
		ordered.verify(occurrences).findByIdAndUserIdForUpdate(OCCURRENCE, USER);
		ordered.verify(completion).completeFromOwnerFact(eq(USER), eq(OCCURRENCE), any(), eq(CORRELATION));
	}

	@Test
	void missingAndDeletedDoNotCreateCompletion() {
		var context = writer.context(USER, OCCURRENCE);
		for (String state : new String[] { "MISSING", "DELETED" }) {
			var result = writer.accept(USER, context, FACT, absent(state), CORRELATION);
			assertThat(result.sourceState()).isEqualTo(state);
			assertThat(result.occurrenceState()).isEqualTo("SCHEDULED");
			assertThat(result.completionEvidence()).isNull();
		}
		verifyNoInteractions(completion);
	}

	@Test
	void deletionKeepsTheAcceptedHistoricalFactEvenAfterTheActionEnds() {
		var verified = NOW.minusSeconds(5);
		occurrence.completeWithEvidence(new EvidenceReference("JOURNAL_AI", "JOURNAL_ENTRY", FACT.toString(),
				"1", NOW.minusSeconds(10), verified), verified);
		action.end(NOW.minusSeconds(2));
		var context = writer.context(USER, OCCURRENCE);

		var result = writer.accept(USER, context, FACT, absent("DELETED"), CORRELATION);

		assertThat(result.sourceState()).isEqualTo("DELETED");
		assertThat(result.occurrenceState()).isEqualTo("COMPLETED");
		assertThat(result.completionEvidence().verifiedAt()).isEqualTo(verified);
		assertThat(result.completedAt()).isEqualTo(verified);
		verifyNoInteractions(completion);
	}

	@Test
	void mismatchedSubjectEntryWritingDateOrPreActivationFactNeverReachesCompletion() {
		var context = writer.context(USER, OCCURRENCE);
		var good = recorded();
		var fact = good.fact();
		for (Evidence bad : new Evidence[] {
				new Evidence(good.schemaVersion(), UUID.randomUUID(), FACT, good.state(), fact, NOW),
				new Evidence(good.schemaVersion(), USER, UUID.randomUUID(), good.state(), fact, NOW),
				new Evidence(good.schemaVersion(), USER, FACT, good.state(), new Fact("JOURNAL_AI", "JOURNAL_ENTRY",
						FACT, "1", fact.occurredAt().minusSeconds(86400)), NOW),
				new Evidence(good.schemaVersion(), USER, FACT, good.state(), new Fact("JOURNAL_AI", "JOURNAL_ENTRY",
						FACT, "1", NOW.minusSeconds(7200)), NOW) }) {
			assertThatThrownBy(() -> writer.accept(USER, context, FACT, bad, CORRELATION))
					.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code())
							.isEqualTo("JOURNAL_ENTRY_EVIDENCE_MISMATCH"));
		}
		verifyNoInteractions(completion);
	}

	@Test
	void actionEndedDuringTheRemoteReadFailsClosed() {
		var context = writer.context(USER, OCCURRENCE);
		action.end(NOW);
		assertThatThrownBy(() -> writer.accept(USER, context, FACT, recorded(), CORRELATION))
				.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code())
						.isEqualTo("SUPPORT_PLAN_NOT_ACTIVE"));
		verifyNoInteractions(completion);
	}

	@Test
	void acceptedWritingFactCannotBeSwappedForAnotherEntryEvenWhenThatEntryIsDeleted() {
		occurrence.completeWithEvidence(new EvidenceReference("JOURNAL_AI", "JOURNAL_ENTRY", FACT.toString(),
				"1", NOW.minusSeconds(10), NOW.minusSeconds(5)), NOW.minusSeconds(5));
		var context = writer.context(USER, OCCURRENCE);
		UUID otherEntry = UUID.randomUUID();
		var deleted = new Evidence("journal-entry-completion-evidence-v1", USER, otherEntry, "DELETED", null, NOW);
		assertThatThrownBy(() -> writer.accept(USER, context, otherEntry, deleted, CORRELATION))
				.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("JOURNAL_ENTRY_EVIDENCE_MISMATCH"));
		verifyNoInteractions(completion);
	}

	@Test
	void futureSkippedCancelledAndWrongActionAreNotEligible() {
		for (String state : new String[] { "SKIPPED", "CANCELLED" }) {
			ReflectionTestUtils.setField(occurrence, "state", state);
			assertThatThrownBy(() -> writer.context(USER, OCCURRENCE)).isInstanceOf(ApiException.class);
		}
		ReflectionTestUtils.setField(occurrence, "state", "SCHEDULED");
		ReflectionTestUtils.setField(occurrence, "localDate", DATE.plusDays(1));
		assertThatThrownBy(() -> writer.context(USER, OCCURRENCE)).isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("JOURNAL_OCCURRENCE_NOT_DUE"));
		ReflectionTestUtils.setField(occurrence, "localDate", DATE);
		ReflectionTestUtils.setField(occurrence, "sourceActionType", "EMOTION_CHECK_IN");
		assertThatThrownBy(() -> writer.context(USER, OCCURRENCE)).isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("JOURNAL_ACTION_REQUIRED"));
	}

	@Test
	void foreignOwnerIsOpaqueAndDoesNotQueryTheSource() {
		assertThatThrownBy(() -> writer.context(UUID.randomUUID(), OCCURRENCE))
				.isInstanceOfSatisfying(ApiException.class, error -> {
					assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
					assertThat(error.code()).isEqualTo("OCCURRENCE_NOT_FOUND");
				});
		verifyNoInteractions(completion);
	}

	@Test
	void orchestratorSeparatesTheRemoteReadFromBothLocalTransactionsAndRejectsAmbientTransactions() throws Exception {
		var local = mock(JournalEntryReconciliationWriter.class);
		var owner = mock(JournalEntryEvidenceClient.class);
		var context = writer.context(USER, OCCURRENCE);
		var fact = recorded();
		when(local.context(USER, OCCURRENCE)).thenReturn(context);
		when(owner.read(USER, FACT, "verified-user-token", CORRELATION)).thenReturn(fact);
		var service = new JournalEntryReconciliationService(local, owner);
		service.reconcile(USER, OCCURRENCE, FACT, "verified-user-token", CORRELATION);
		var order = inOrder(local, owner);
		order.verify(local).context(USER, OCCURRENCE);
		order.verify(owner).read(USER, FACT, "verified-user-token", CORRELATION);
		order.verify(local).accept(USER, context, FACT, fact, CORRELATION);
		assertThat(JournalEntryReconciliationService.class.getMethod("reconcile", UUID.class, UUID.class, UUID.class,
				String.class, UUID.class).getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.NEVER);
	}

	private Evidence recorded() {
		return new Evidence("journal-entry-completion-evidence-v1", USER, FACT, "RECORDED",
				new Fact("JOURNAL_AI", "JOURNAL_ENTRY", FACT, "1", NOW.minusSeconds(10)), NOW);
	}

	private Evidence absent(String state) {
		return new Evidence("journal-entry-completion-evidence-v1", USER, FACT, state, null, NOW);
	}
}

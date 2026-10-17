package com.mentalbridge.consultation.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class ChatSessionCompletionPolicyTests {

	private static final Instant START = Instant.parse("2026-10-01T02:00:00Z");
	private static final Instant END = START.plus(60, ChronoUnit.MINUTES);
	private final ChatSessionCompletionPolicy policy = new ChatSessionCompletionPolicy();

	@Test
	void completesAtExactlyThirtyMinutesOfCommonPresence() {
		assertThat(evaluate(completeEvidence(30, 30))).isEqualTo(ChatSessionOutcome.COMPLETED);
	}

	@Test
	void doesNotCompleteBelowThirtyMinutes() {
		var evidence = completeEvidence(30, 30);
		evidence.removeIf(fact -> fact.role().equals("SPECIALIST")
				&& fact.type() == ChatEvidenceRequest.Type.PRESENCE_INTERVAL);
		evidence.add(presence("SPECIALIST", 0, 29, 59));

		assertThat(evaluate(evidence)).isEqualTo(ChatSessionOutcome.INSUFFICIENT_EVIDENCE);
	}

	@Test
	void sumsNonContiguousPresenceAcrossReconnects() {
		var evidence = participationWithoutPresence();
		evidence.addAll(List.of(presence("USER", 0, 15), presence("USER", 20, 35),
				presence("SPECIALIST", 0, 15), presence("SPECIALIST", 20, 35)));

		assertThat(evaluate(evidence)).isEqualTo(ChatSessionOutcome.COMPLETED);
	}

	@Test
	void overlappingIntervalsAreMergedInsteadOfDoubleCounted() {
		var evidence = participationWithoutPresence();
		evidence.addAll(List.of(presence("USER", 0, 20), presence("USER", 10, 30),
				presence("SPECIALIST", 0, 29)));

		assertThat(evaluate(evidence)).isEqualTo(ChatSessionOutcome.INSUFFICIENT_EVIDENCE);
	}

	@Test
	void requiresAnAcceptedMessageFromEachParticipant() {
		var evidence = completeEvidence(40, 40);
		evidence.removeIf(fact -> fact.role().equals("SPECIALIST")
				&& fact.type() == ChatEvidenceRequest.Type.ACCEPTED_MESSAGE);

		assertThat(evaluate(evidence)).isEqualTo(ChatSessionOutcome.INSUFFICIENT_EVIDENCE);
	}

	@Test
	void ignoresAMessageThatOccurredAfterTheExclusiveEnd() {
		var evidence = completeEvidence(30, 30);
		evidence.removeIf(fact -> fact.role().equals("SPECIALIST")
				&& fact.type() == ChatEvidenceRequest.Type.ACCEPTED_MESSAGE);
		evidence.add(new ChatSessionCompletionPolicy.Evidence("SPECIALIST",
				ChatEvidenceRequest.Type.ACCEPTED_MESSAGE, null, END));

		assertThat(evaluate(evidence)).isEqualTo(ChatSessionOutcome.INSUFFICIENT_EVIDENCE);
	}

	@Test
	void ignoresAllEvidenceThatOccurredAfterTheExclusiveEnd() {
		assertThat(evaluate(List.of(new ChatSessionCompletionPolicy.Evidence("USER",
				ChatEvidenceRequest.Type.ACCEPTED_MESSAGE, null, END.plusSeconds(1)))))
				.isEqualTo(ChatSessionOutcome.BOTH_NO_SHOW);
	}

	@Test
	void classifiesUserNoShowAtExactlyFifteenMinutes() {
		assertThat(evaluate(List.of(checkIn("SPECIALIST"), presence("SPECIALIST", 0, 15))))
				.isEqualTo(ChatSessionOutcome.USER_NO_SHOW);
	}

	@Test
	void doesNotClassifyNoShowBelowFifteenMinutes() {
		assertThat(evaluate(List.of(checkIn("SPECIALIST"), presence("SPECIALIST", 0, 14, 59))))
				.isEqualTo(ChatSessionOutcome.INSUFFICIENT_EVIDENCE);
	}

	@Test
	void classifiesSpecialistNoShowSymmetrically() {
		assertThat(evaluate(List.of(checkIn("USER"), presence("USER", 3, 18))))
				.isEqualTo(ChatSessionOutcome.SPECIALIST_NO_SHOW);
	}

	@Test
	void classifiesBothNoShowOnlyWhenNeitherHasEvidence() {
		assertThat(evaluate(List.of())).isEqualTo(ChatSessionOutcome.BOTH_NO_SHOW);
		assertThat(evaluate(List.of(message("USER"))))
				.isEqualTo(ChatSessionOutcome.INSUFFICIENT_EVIDENCE);
	}

	@Test
	void clampsPresenceToTheScheduledWindow() {
		var evidence = participationWithoutPresence();
		evidence.addAll(List.of(
				new ChatSessionCompletionPolicy.Evidence("USER", ChatEvidenceRequest.Type.PRESENCE_INTERVAL,
						START.minusSeconds(600), START.plusSeconds(1200)),
				new ChatSessionCompletionPolicy.Evidence("SPECIALIST", ChatEvidenceRequest.Type.PRESENCE_INTERVAL,
						START.minusSeconds(600), START.plusSeconds(1200))));

		assertThat(evaluate(evidence)).isEqualTo(ChatSessionOutcome.INSUFFICIENT_EVIDENCE);
	}

	@Test
	void producesTheSameOutcomeWhenEvidenceDeliveryIsReordered() {
		var evidence = completeEvidence(30, 30);
		var reversed = new ArrayList<>(evidence);
		Collections.reverse(reversed);

		assertThat(evaluate(evidence)).isEqualTo(ChatSessionOutcome.COMPLETED);
		assertThat(evaluate(reversed)).isEqualTo(ChatSessionOutcome.COMPLETED);
	}

	@Test
	void anEarlyCheckInCountsArrivalButNotPresenceMinutes() {
		var evidence = completeEvidence(30, 30);
		evidence.removeIf(fact -> fact.type() == ChatEvidenceRequest.Type.CHECK_IN);
		evidence.add(new ChatSessionCompletionPolicy.Evidence("USER", ChatEvidenceRequest.Type.CHECK_IN,
				null, START.minus(10, ChronoUnit.MINUTES)));
		evidence.add(new ChatSessionCompletionPolicy.Evidence("SPECIALIST", ChatEvidenceRequest.Type.CHECK_IN,
				null, START.minus(10, ChronoUnit.MINUTES)));

		assertThat(evaluate(evidence)).isEqualTo(ChatSessionOutcome.COMPLETED);
	}

	private ChatSessionOutcome evaluate(List<ChatSessionCompletionPolicy.Evidence> evidence) {
		return policy.evaluate(START, END, evidence).outcome();
	}

	private List<ChatSessionCompletionPolicy.Evidence> completeEvidence(int userMinutes, int specialistMinutes) {
		var evidence = participationWithoutPresence();
		evidence.add(presence("USER", 0, userMinutes));
		evidence.add(presence("SPECIALIST", 0, specialistMinutes));
		return evidence;
	}

	private List<ChatSessionCompletionPolicy.Evidence> participationWithoutPresence() {
		return new ArrayList<>(List.of(checkIn("USER"), checkIn("SPECIALIST"), message("USER"), message("SPECIALIST")));
	}

	private ChatSessionCompletionPolicy.Evidence checkIn(String role) {
		return new ChatSessionCompletionPolicy.Evidence(role, ChatEvidenceRequest.Type.CHECK_IN, null, START);
	}

	private ChatSessionCompletionPolicy.Evidence message(String role) {
		return new ChatSessionCompletionPolicy.Evidence(role, ChatEvidenceRequest.Type.ACCEPTED_MESSAGE, null,
				START.plusSeconds(30));
	}

	private ChatSessionCompletionPolicy.Evidence presence(String role, int startMinute, int endMinute) {
		return new ChatSessionCompletionPolicy.Evidence(role, ChatEvidenceRequest.Type.PRESENCE_INTERVAL,
				START.plus(startMinute, ChronoUnit.MINUTES), START.plus(endMinute, ChronoUnit.MINUTES));
	}

	private ChatSessionCompletionPolicy.Evidence presence(String role, int startMinute, int endMinute, int endSecond) {
		return new ChatSessionCompletionPolicy.Evidence(role, ChatEvidenceRequest.Type.PRESENCE_INTERVAL,
				START.plus(startMinute, ChronoUnit.MINUTES),
				START.plus(endMinute, ChronoUnit.MINUTES).plus(endSecond, ChronoUnit.SECONDS));
	}
}

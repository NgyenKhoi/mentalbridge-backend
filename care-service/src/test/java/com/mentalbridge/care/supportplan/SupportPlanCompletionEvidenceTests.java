package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.EvidenceReference;

class SupportPlanCompletionEvidenceTests {

	@Test
	void recheckingTheSameFactDoesNotChangeItsAcceptedIdentityOrHistory() {
		var occurredAt = Instant.parse("2026-10-13T01:00:00Z");
		var verifiedAt = occurredAt.plusSeconds(1);
		var factId = UUID.randomUUID().toString();
		var occurrence = new SupportPlanActivityOccurrenceEntity();
		occurrence.completeWithEvidence(evidence(factId, "1", occurredAt, verifiedAt), verifiedAt);

		assertThat(occurrence.evidenceEquals(evidence(factId, "1", occurredAt, verifiedAt.plusSeconds(30))))
				.isTrue();
		assertThat(occurrence.evidenceVerifiedAt()).isEqualTo(verifiedAt);
		assertThat(occurrence.completedAt()).isEqualTo(verifiedAt);
		assertThat(occurrence.evidenceEquals(evidence(UUID.randomUUID().toString(), "1", occurredAt, verifiedAt)))
				.isFalse();
		assertThat(occurrence.evidenceEquals(evidence(factId, "2", occurredAt, verifiedAt))).isFalse();
		assertThat(occurrence.evidenceEquals(evidence(factId, "1", occurredAt.minusSeconds(1), verifiedAt)))
				.isFalse();
		assertThat(occurrence.evidenceEquals(new EvidenceReference("JOURNAL_AI", "JOURNAL_ENTRY", factId,
				"1", occurredAt, verifiedAt))).isFalse();
		assertThat(new SupportPlanActivityOccurrenceEntity().evidenceEquals(
				evidence(factId, "1", occurredAt, verifiedAt))).isFalse();
	}

	private EvidenceReference evidence(String factId, String revision, Instant occurredAt, Instant verifiedAt) {
		return new EvidenceReference("JOURNAL_AI", "EMOTION_CHECK_IN", factId, revision, occurredAt, verifiedAt);
	}
}

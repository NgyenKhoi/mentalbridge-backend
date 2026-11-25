package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.EvidenceReference;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.Provenance;
import com.mentalbridge.care.supportplan.PlanActionSourcePolicy.SourceReference;

class PlanActionSourcePolicyTests {

	private static final Instant NOW = Instant.parse("2026-10-11T08:00:00Z");
	private static final Provenance USER_PROVENANCE = new Provenance("USER_SELECTED", null, null, NOW);

	private final PlanActionSourcePolicy policy = new PlanActionSourcePolicy();

	@Test
	void acceptsEveryCanonicalActionSourceShape() {
		var resource = new SourceReference("CONTENT", "RESOURCE_VERSION", UUID.randomUUID().toString(), "12",
				null, UUID.randomUUID(), "content-eligibility-v1", NOW);
		var journal = new SourceReference("JOURNAL_AI", "JOURNAL_TEMPLATE", "daily-reflection", "v2",
				"journal-template-v2", null, null, NOW);
		var emotion = new SourceReference("JOURNAL_AI", "EMOTION_CHECK_IN_CAPABILITY", "emotion-check-in",
				null, null, null, null, null);
		var pathway = new SourceReference("CONSULTATION", "PROFESSIONAL_SUPPORT_PATHWAY", "wellbeing-care",
				"v1", "routing-v3", null, null, NOW);
		var reassessment = new SourceReference("CARE", "QUESTIONNAIRE_DEFINITION",
				UUID.randomUUID().toString(), "phq9-vi-vn-capstone-v2", "screening-v2", null, null, NOW);

		assertThatCode(() -> policy.validateSource("RESOURCE", resource, USER_PROVENANCE)).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateSource("JOURNAL", null, USER_PROVENANCE)).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateSource("JOURNAL", journal, USER_PROVENANCE)).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateSource("EMOTION_CHECK_IN", null, USER_PROVENANCE)).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateSource("EMOTION_CHECK_IN", emotion, USER_PROVENANCE)).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateSource("ROUTINE_SELF_MANAGEMENT", null, USER_PROVENANCE)).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateSource("PROFESSIONAL_SUPPORT", pathway, USER_PROVENANCE)).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateSource("REASSESSMENT_SCREENING", reassessment, USER_PROVENANCE)).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateSource("SELF_REPORT_REVIEW", null, USER_PROVENANCE)).doesNotThrowAnyException();
	}

	@Test
	void rejectsUnknownTypesMalformedReferencesAndCrossOwnerSources() {
		var malformedResource = new SourceReference("CONTENT", "RESOURCE_VERSION", "not-a-uuid", "latest",
				null, UUID.randomUUID(), "content-eligibility-v1", NOW);
		var crossOwnerJournal = new SourceReference("CONTENT", "JOURNAL_TEMPLATE", "daily-reflection", null,
				null, null, null, null);
		var partialEmotion = new SourceReference("JOURNAL_AI", null, "emotion-check-in", null,
				null, null, null, null);

		assertThatThrownBy(() -> policy.validateSource("UNKNOWN", null, USER_PROVENANCE))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy.validateSource("RESOURCE", malformedResource, USER_PROVENANCE))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy.validateSource("JOURNAL", crossOwnerJournal, USER_PROVENANCE))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy.validateSource("EMOTION_CHECK_IN", partialEmotion, USER_PROVENANCE))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy.validateSource("ROUTINE_SELF_MANAGEMENT", crossOwnerJournal, USER_PROVENANCE))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy.validateSource("JOURNAL", null,
				new Provenance("USER_SELECTED", "proposal", null, NOW)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requiresActionSpecificCompletionEvidence() {
		var userAcknowledgement = evidence("CARE", "USER_ACKNOWLEDGEMENT");
		var selfReport = evidence("CARE", "SELF_REPORT_REVIEW");
		var journal = evidence("JOURNAL_AI", "JOURNAL_ENTRY");
		var emotion = evidence("JOURNAL_AI", "EMOTION_CHECK_IN");
		var appointment = evidence("CONSULTATION", "APPOINTMENT_FULFILMENT");
		var assessment = evidence("CARE", "ASSESSMENT_HISTORY");

		assertThatCode(() -> policy.validateCompletionEvidence("RESOURCE", userAcknowledgement)).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateCompletionEvidence("ROUTINE_SELF_MANAGEMENT", userAcknowledgement)).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateCompletionEvidence("SELF_REPORT_REVIEW", selfReport)).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateCompletionEvidence("JOURNAL", journal)).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateCompletionEvidence("EMOTION_CHECK_IN", emotion)).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateCompletionEvidence("PROFESSIONAL_SUPPORT", appointment)).doesNotThrowAnyException();
		assertThatCode(() -> policy.validateCompletionEvidence("REASSESSMENT_SCREENING", assessment)).doesNotThrowAnyException();

		assertThatThrownBy(() -> policy.validateCompletionEvidence("JOURNAL", userAcknowledgement))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy.validateCompletionEvidence("RESOURCE", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy.validateCompletionEvidence("PROFESSIONAL_SUPPORT",
				new EvidenceReference("CONSULTATION", "APPOINTMENT_STATUS", "appointment", null, NOW, NOW)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> policy.validateCompletionEvidence("JOURNAL",
				new EvidenceReference("JOURNAL_AI", "JOURNAL_ENTRY", "entry", "1", NOW,
						NOW.minusSeconds(1))))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private EvidenceReference evidence(String owner, String kind) {
		return new EvidenceReference(owner, kind, UUID.randomUUID().toString(), "1", NOW, NOW);
	}
}

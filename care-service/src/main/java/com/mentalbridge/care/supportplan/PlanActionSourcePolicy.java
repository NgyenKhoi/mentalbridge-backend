package com.mentalbridge.care.supportplan;

import java.util.UUID;

final class PlanActionSourcePolicy {

	void validateSource(String actionType, SourceReference source, Provenance provenance) {
		var type = actionType(actionType);
		validateProvenance(provenance);
		switch (type) {
			case RESOURCE -> requireResource(source);
			case JOURNAL -> requireOptionalSource(source, "JOURNAL_AI", "JOURNAL_TEMPLATE");
			case EMOTION_CHECK_IN -> requireOptionalSource(source, "JOURNAL_AI", "EMOTION_CHECK_IN_CAPABILITY");
			case ROUTINE_SELF_MANAGEMENT, SELF_REPORT_REVIEW -> requireNoSource(source);
			case PROFESSIONAL_SUPPORT -> requireProfessionalSupport(source);
			case REASSESSMENT_SCREENING -> requireReassessment(source);
		}
	}

	void validateCompletionEvidence(String actionType, EvidenceReference evidence) {
		switch (actionType(actionType)) {
			case RESOURCE, ROUTINE_SELF_MANAGEMENT -> requireCareEvidence(evidence, "USER_ACKNOWLEDGEMENT");
			case SELF_REPORT_REVIEW -> requireCareEvidence(evidence, "SELF_REPORT_REVIEW");
			case JOURNAL -> requireEvidence(evidence, "JOURNAL_AI", "JOURNAL_ENTRY");
			case EMOTION_CHECK_IN -> requireEvidence(evidence, "JOURNAL_AI", "EMOTION_CHECK_IN");
			case PROFESSIONAL_SUPPORT -> requireEvidence(evidence, "CONSULTATION", "APPOINTMENT_FULFILMENT");
			case REASSESSMENT_SCREENING -> requireEvidence(evidence, "CARE", "ASSESSMENT_HISTORY");
		}
	}

	private ActionType actionType(String value) {
		try {
			return ActionType.valueOf(value);
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException("Unsupported PlanAction type", exception);
		}
	}

	private void validateProvenance(Provenance provenance) {
		if (provenance == null || !oneOf(provenance.type(), "USER_SELECTED", "SUPPORT_GUIDE", "SPECIALIST",
				"SYSTEM_POLICY") || provenance.acceptedAt() == null
				|| !pairedOptionalText(provenance.referenceId(), 255, provenance.referenceVersion(), 128)) {
			throw new IllegalArgumentException("PlanAction provenance is required");
		}
	}

	private void requireResource(SourceReference source) {
		if (!source(source, "CONTENT", "RESOURCE_VERSION") || !uuid(source.id())
				|| !nonNegativeLong(source.revision()) || !optionalText(source.policyVersion(), 128)
				|| source.publicationId() == null || !text(source.eligibilityPolicyVersion(), 128)
				|| source.verifiedAt() == null) {
			throw invalidSource();
		}
	}

	private void requireOptionalSource(SourceReference source, String owner, String kind) {
		if (source == null) return;
		if (!source(source, owner, kind) || !text(source.id(), 255)
				|| !optionalText(source.revision(), 128) || !optionalText(source.policyVersion(), 128)
				|| source.publicationId() != null || source.eligibilityPolicyVersion() != null) {
			throw invalidSource();
		}
	}

	private void requireNoSource(SourceReference source) {
		if (source != null) throw invalidSource();
	}

	private void requireProfessionalSupport(SourceReference source) {
		if (source == null || !"CONSULTATION".equals(source.owner())
				|| !oneOf(source.kind(), "PROFESSIONAL_SUPPORT_PATHWAY", "APPOINTMENT")
				|| !text(source.id(), 255) || !optionalText(source.revision(), 128)
				|| !optionalText(source.policyVersion(), 128) || source.publicationId() != null
				|| source.eligibilityPolicyVersion() != null) {
			throw invalidSource();
		}
	}

	private void requireReassessment(SourceReference source) {
		if (!source(source, "CARE", "QUESTIONNAIRE_DEFINITION") || !uuid(source.id())
				|| !text(source.revision(), 128) || !optionalText(source.policyVersion(), 128)
				|| source.publicationId() != null || source.eligibilityPolicyVersion() != null) {
			throw invalidSource();
		}
	}

	private void requireCareEvidence(EvidenceReference evidence, String kind) {
		if (evidence == null || !"CARE".equals(evidence.owner())
				|| !kind.equals(evidence.kind())
				|| !text(evidence.id(), 255) || !optionalText(evidence.revision(), 128)
				|| !validEvidenceTimes(evidence)) {
			throw invalidEvidence();
		}
	}

	private void requireEvidence(EvidenceReference evidence, String owner, String kind) {
		if (evidence == null || !owner.equals(evidence.owner()) || !kind.equals(evidence.kind())
				|| !text(evidence.id(), 255) || !optionalText(evidence.revision(), 128)
				|| !validEvidenceTimes(evidence)) {
			throw invalidEvidence();
		}
	}

	private boolean validEvidenceTimes(EvidenceReference evidence) {
		return evidence.occurredAt() != null && evidence.verifiedAt() != null
				&& !evidence.verifiedAt().isBefore(evidence.occurredAt());
	}

	private boolean source(SourceReference source, String owner, String kind) {
		return source != null && owner.equals(source.owner()) && kind.equals(source.kind());
	}

	private boolean nonNegativeLong(String value) {
		try {
			return value != null && Long.parseLong(value) >= 0 && Long.toString(Long.parseLong(value)).equals(value);
		}
		catch (NumberFormatException exception) {
			return false;
		}
	}

	private boolean uuid(String value) {
		try {
			return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value);
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private boolean text(String value, int maximumLength) {
		return value != null && !value.isBlank() && value.equals(value.strip())
				&& value.length() <= maximumLength;
	}

	private boolean optionalText(String value, int maximumLength) {
		return value == null || text(value, maximumLength);
	}

	private boolean pairedOptionalText(String first, int firstMaximum, String second, int secondMaximum) {
		return first == null && second == null
				|| text(first, firstMaximum) && text(second, secondMaximum);
	}

	private boolean oneOf(String value, String... expected) {
		if (value == null) return false;
		for (String candidate : expected) {
			if (candidate.equals(value)) return true;
		}
		return false;
	}

	private IllegalArgumentException invalidSource() {
		return new IllegalArgumentException("PlanAction source does not match its action type");
	}

	private IllegalArgumentException invalidEvidence() {
		return new IllegalArgumentException("Completion evidence does not match its action type");
	}

	enum ActionType {
		RESOURCE,
		JOURNAL,
		EMOTION_CHECK_IN,
		ROUTINE_SELF_MANAGEMENT,
		PROFESSIONAL_SUPPORT,
		REASSESSMENT_SCREENING,
		SELF_REPORT_REVIEW
	}

	record SourceReference(String owner, String kind, String id, String revision, String policyVersion,
			UUID publicationId, String eligibilityPolicyVersion, java.time.Instant verifiedAt) { }
	record Provenance(String type, String referenceId, String referenceVersion,
			java.time.Instant acceptedAt) { }
	record EvidenceReference(String owner, String kind, String id, String revision,
			java.time.Instant occurredAt, java.time.Instant verifiedAt) { }
}

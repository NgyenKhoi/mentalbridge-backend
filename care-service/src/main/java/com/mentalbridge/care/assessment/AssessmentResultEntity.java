package com.mentalbridge.care.assessment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "assessment_result")
class AssessmentResultEntity {

	@Id
	private UUID submissionId;

	private short totalScore;

	@Enumerated(EnumType.STRING)
	private ScreeningLevel screeningLevel;

	private String scoringVersion;

	private Boolean safetyItemPositive;

	@Enumerated(EnumType.STRING)
	private SafetyStatus safetyStatus;

	private String safetyPolicyVersion;

	private String disclaimerCode;

	private Instant calculatedAt;

	private Instant createdAt;

	protected AssessmentResultEntity() {
	}

	AssessmentResultEntity(UUID submissionId, AssessmentScoringPolicy.ScoredResult scored, String scoringVersion,
			String safetyPolicyVersion, Instant calculatedAt) {
		this.submissionId = submissionId;
		this.totalScore = (short) scored.totalScore();
		this.screeningLevel = scored.screeningLevel();
		this.scoringVersion = scoringVersion;
		this.safetyItemPositive = scored.safetyItemPositive();
		this.safetyStatus = scored.safetyStatus();
		this.safetyPolicyVersion = safetyPolicyVersion;
		this.disclaimerCode = "SCREENING_NOT_DIAGNOSIS";
		this.calculatedAt = calculatedAt;
		this.createdAt = calculatedAt;
	}

	int totalScore() {
		return totalScore;
	}

	ScreeningLevel screeningLevel() {
		return screeningLevel;
	}

	String scoringVersion() {
		return scoringVersion;
	}

	SafetyStatus safetyStatus() {
		return safetyStatus;
	}

	String safetyPolicyVersion() {
		return safetyPolicyVersion;
	}

	String disclaimerCode() {
		return disclaimerCode;
	}
}

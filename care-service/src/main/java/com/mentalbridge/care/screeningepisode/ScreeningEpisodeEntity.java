package com.mentalbridge.care.screeningepisode;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "screening_episode")
class ScreeningEpisodeEntity {

	@Id
	private UUID id;

	private UUID userId;
	private String purpose;
	private String status;
	private UUID phq9AssessmentId;
	private UUID gad7AssessmentId;
	private UUID supportEvaluationId;
	private UUID presentationEvaluationId;
	private Instant createdAt;
	private Instant updatedAt;
	private Instant completedAt;

	@Version
	private long version;

	protected ScreeningEpisodeEntity() {
	}

	ScreeningEpisodeEntity(UUID id, UUID userId, String purpose, Instant createdAt) {
		this.id = id;
		this.userId = userId;
		this.purpose = purpose;
		this.status = "IN_PROGRESS";
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	void attachAssessment(String instrument, UUID assessmentId, Instant updatedAt) {
		if ("PHQ9".equals(instrument)) {
			phq9AssessmentId = assessmentId;
		}
		else {
			gad7AssessmentId = assessmentId;
		}
		status = phq9AssessmentId != null && gad7AssessmentId != null ? "READY" : "IN_PROGRESS";
		this.updatedAt = updatedAt;
	}

	void complete(UUID supportEvaluationId, UUID presentationEvaluationId, Instant completedAt) {
		this.supportEvaluationId = supportEvaluationId;
		this.presentationEvaluationId = presentationEvaluationId;
		this.status = "COMPLETED";
		this.updatedAt = completedAt;
		this.completedAt = completedAt;
	}

	UUID id() { return id; }
	UUID userId() { return userId; }
	String purpose() { return purpose; }
	String status() { return status; }
	UUID phq9AssessmentId() { return phq9AssessmentId; }
	UUID gad7AssessmentId() { return gad7AssessmentId; }
	UUID supportEvaluationId() { return supportEvaluationId; }
	UUID presentationEvaluationId() { return presentationEvaluationId; }
	Instant createdAt() { return createdAt; }
	Instant updatedAt() { return updatedAt; }
	Instant completedAt() { return completedAt; }
	long version() { return version; }
}

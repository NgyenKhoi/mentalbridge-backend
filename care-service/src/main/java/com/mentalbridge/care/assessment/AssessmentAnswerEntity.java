package com.mentalbridge.care.assessment;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@IdClass(AssessmentAnswerId.class)
@Table(name = "assessment_answer")
class AssessmentAnswerEntity {

	@Id
	private UUID submissionId;

	private UUID definitionId;

	@Id
	private UUID questionId;

	private short answerValue;

	protected AssessmentAnswerEntity() {
	}

	AssessmentAnswerEntity(UUID submissionId, UUID definitionId, UUID questionId, int answerValue) {
		this.submissionId = submissionId;
		this.definitionId = definitionId;
		this.questionId = questionId;
		this.answerValue = (short) answerValue;
	}
}

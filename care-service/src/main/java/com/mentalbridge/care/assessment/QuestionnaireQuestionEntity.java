package com.mentalbridge.care.assessment;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "questionnaire_question")
class QuestionnaireQuestionEntity {

	@Id
	private UUID id;

	private UUID definitionId;

	private short itemNumber;

	private String prompt;

	private boolean safetyItem;

	protected QuestionnaireQuestionEntity() {
	}

	UUID id() {
		return id;
	}

	short itemNumber() {
		return itemNumber;
	}

	String prompt() {
		return prompt;
	}

	boolean safetyItem() {
		return safetyItem;
	}
}

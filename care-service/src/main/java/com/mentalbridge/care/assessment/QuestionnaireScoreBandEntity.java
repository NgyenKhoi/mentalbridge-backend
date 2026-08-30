package com.mentalbridge.care.assessment;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@IdClass(QuestionnaireScoreBandId.class)
@Table(name = "questionnaire_score_band")
class QuestionnaireScoreBandEntity {

	@Id
	private UUID definitionId;

	@Id
	@Enumerated(EnumType.STRING)
	private ScreeningLevel code;

	private short minimumScore;

	private short maximumScore;

	private short ordinal;

	protected QuestionnaireScoreBandEntity() {
	}

	ScreeningLevel code() {
		return code;
	}

	short minimumScore() {
		return minimumScore;
	}

	short maximumScore() {
		return maximumScore;
	}
}

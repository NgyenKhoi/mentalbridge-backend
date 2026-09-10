package com.mentalbridge.care.assessment;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "questionnaire_definition")
class QuestionnaireDefinitionEntity {

	@Id
	private UUID id;

	private String instrument;

	private String version;

	private String locale;

	private String title;

	private short referencePeriodDays;

	private short expectedQuestionCount;

	private String scoringVersion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private JsonNode responseOptions;

	private String status;

	private Instant publishedAt;

	protected QuestionnaireDefinitionEntity() {
	}

	UUID id() {
		return id;
	}

	String instrument() {
		return instrument;
	}

	String version() {
		return version;
	}

	String locale() {
		return locale;
	}

	String title() {
		return title;
	}

	short referencePeriodDays() {
		return referencePeriodDays;
	}

	short expectedQuestionCount() {
		return expectedQuestionCount;
	}

	String scoringVersion() {
		return scoringVersion;
	}

	JsonNode responseOptions() {
		return responseOptions;
	}

	boolean published() {
		return "PUBLISHED".equals(status) && publishedAt != null;
	}

	boolean readable() {
		return ("PUBLISHED".equals(status) || "RETIRED".equals(status)) && publishedAt != null;
	}
}

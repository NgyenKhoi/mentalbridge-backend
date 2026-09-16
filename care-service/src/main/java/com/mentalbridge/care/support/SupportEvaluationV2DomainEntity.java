package com.mentalbridge.care.support;

import java.util.UUID;

import com.mentalbridge.care.assessment.ScreeningLevel;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_evaluation_v2_domain")
class SupportEvaluationV2DomainEntity {

	@Id
	private UUID id;

	private UUID supportEvaluationId;

	private short ordinal;

	private UUID assessmentId;

	private UUID definitionId;

	private String instrument;

	@Enumerated(EnumType.STRING)
	private ScreeningDomain domain;

	private String questionnaireVersion;

	private String scoringVersion;

	@Enumerated(EnumType.STRING)
	private ScreeningLevel screeningLevel;

	@Enumerated(EnumType.STRING)
	private DomainSupportPathway supportPathway;

	@Enumerated(EnumType.STRING)
	private DomainReasonCode reasonCode;

	protected SupportEvaluationV2DomainEntity() {
	}

	SupportEvaluationV2DomainEntity(UUID id, UUID supportEvaluationId, short ordinal, UUID assessmentId,
			UUID definitionId, String instrument, ScreeningDomain domain, String questionnaireVersion,
			String scoringVersion, ScreeningLevel screeningLevel, DomainSupportPathway supportPathway,
			DomainReasonCode reasonCode) {
		this.id = id;
		this.supportEvaluationId = supportEvaluationId;
		this.ordinal = ordinal;
		this.assessmentId = assessmentId;
		this.definitionId = definitionId;
		this.instrument = instrument;
		this.domain = domain;
		this.questionnaireVersion = questionnaireVersion;
		this.scoringVersion = scoringVersion;
		this.screeningLevel = screeningLevel;
		this.supportPathway = supportPathway;
		this.reasonCode = reasonCode;
	}

	UUID assessmentId() { return assessmentId; }
	UUID definitionId() { return definitionId; }
	String instrument() { return instrument; }
	ScreeningDomain domain() { return domain; }
	String questionnaireVersion() { return questionnaireVersion; }
	String scoringVersion() { return scoringVersion; }
	ScreeningLevel screeningLevel() { return screeningLevel; }
	DomainSupportPathway supportPathway() { return supportPathway; }
	DomainReasonCode reasonCode() { return reasonCode; }
}

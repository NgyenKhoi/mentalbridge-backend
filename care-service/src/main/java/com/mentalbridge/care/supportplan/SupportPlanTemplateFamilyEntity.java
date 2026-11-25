package com.mentalbridge.care.supportplan;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_plan_template_family")
class SupportPlanTemplateFamilyEntity {
	@Id private UUID id;
	private UUID supportPlanId;
	private short ordinal;
	private String family;
	private int templateVersion;
	private String targetDomain;
	private String focusTitle;
	private String focusRationale;
	private String focusReasonCode;

	protected SupportPlanTemplateFamilyEntity() { }

	SupportPlanTemplateFamilyEntity(UUID id, UUID supportPlanId, int ordinal, String family, String targetDomain) {
		this(id, supportPlanId, ordinal, family, targetDomain, null, null, null);
	}

	SupportPlanTemplateFamilyEntity(UUID id, UUID supportPlanId, int ordinal, String family, String targetDomain,
			String focusTitle, String focusRationale, String focusReasonCode) {
		this.id = id;
		this.supportPlanId = supportPlanId;
		this.ordinal = (short) ordinal;
		this.family = family;
		this.templateVersion = 1;
		this.targetDomain = targetDomain;
		this.focusTitle = focusTitle;
		this.focusRationale = focusRationale;
		this.focusReasonCode = focusReasonCode;
	}

	String family() { return family; }
	int templateVersion() { return templateVersion; }
	String targetDomain() { return targetDomain; }
	String focusTitle() { return focusTitle; }
	String focusRationale() { return focusRationale; }
	String focusReasonCode() { return focusReasonCode; }
}

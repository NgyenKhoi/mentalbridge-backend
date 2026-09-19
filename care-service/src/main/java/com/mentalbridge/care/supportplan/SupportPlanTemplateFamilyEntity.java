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

	protected SupportPlanTemplateFamilyEntity() { }

	SupportPlanTemplateFamilyEntity(UUID id, UUID supportPlanId, int ordinal, String family, String targetDomain) {
		this.id = id;
		this.supportPlanId = supportPlanId;
		this.ordinal = (short) ordinal;
		this.family = family;
		this.templateVersion = 1;
		this.targetDomain = targetDomain;
	}

	String family() { return family; }
	int templateVersion() { return templateVersion; }
	String targetDomain() { return targetDomain; }
}

package com.mentalbridge.care.supportplan;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_plan_slot_alternative")
class SupportPlanSlotAlternativeEntity {
	@Id private UUID id;
	private UUID supportPlanSlotId;
	private short ordinal;
	private UUID resourceId;
	private long contentVersion;
	private UUID publicationId;
	private String eligibilityRole;
	private String category;
	private String title;
	private String summary;
	private String externalUrl;

	protected SupportPlanSlotAlternativeEntity() { }

	SupportPlanSlotAlternativeEntity(UUID id, UUID supportPlanSlotId, int ordinal,
			SupportPlanPolicy.ResourceDraft resource) {
		this.id = id;
		this.supportPlanSlotId = supportPlanSlotId;
		this.ordinal = (short) ordinal;
		this.resourceId = resource.resourceId();
		this.contentVersion = resource.contentVersion();
		this.publicationId = resource.publicationId();
		this.eligibilityRole = resource.role();
		this.category = resource.category();
		this.title = resource.title();
		this.summary = resource.summary();
		this.externalUrl = resource.externalUrl();
	}

	SupportPlanPolicy.ResourceDraft resource() {
		return new SupportPlanPolicy.ResourceDraft(resourceId, contentVersion, publicationId, eligibilityRole,
				category, title, summary, externalUrl);
	}
}

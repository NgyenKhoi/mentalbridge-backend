package com.mentalbridge.care.supportplan;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_plan_slot")
class SupportPlanSlotEntity {
	@Id private UUID id;
	private UUID supportPlanId;
	private short ordinal;
	private String slotKey;
	private String slotKind;
	private String targetDomain;
	private String purposeCode;
	private UUID selectedResourceId;
	private long selectedContentVersion;
	private UUID selectedPublicationId;
	private String selectedRole;
	private String selectedCategory;
	private String selectedTitle;
	private String selectedSummary;
	private String selectedExternalUrl;

	protected SupportPlanSlotEntity() { }

	SupportPlanSlotEntity(UUID id, UUID supportPlanId, int ordinal, SupportPlanPolicy.SlotDraft slot) {
		this.id = id;
		this.supportPlanId = supportPlanId;
		this.ordinal = (short) ordinal;
		this.slotKey = slot.slotId();
		this.slotKind = slot.kind();
		this.targetDomain = slot.targetDomain();
		this.purposeCode = slot.purposeCode();
		var resource = slot.selectedResource();
		this.selectedResourceId = resource.resourceId();
		this.selectedContentVersion = resource.contentVersion();
		this.selectedPublicationId = resource.publicationId();
		this.selectedRole = resource.role();
		this.selectedCategory = resource.category();
		this.selectedTitle = resource.title();
		this.selectedSummary = resource.summary();
		this.selectedExternalUrl = resource.externalUrl();
	}

	UUID id() { return id; }
	String slotKey() { return slotKey; }
	String slotKind() { return slotKind; }
	String targetDomain() { return targetDomain; }
	String purposeCode() { return purposeCode; }
	SupportPlanPolicy.ResourceDraft selectedResource() {
		return new SupportPlanPolicy.ResourceDraft(selectedResourceId, selectedContentVersion, selectedPublicationId,
				selectedRole, selectedCategory, selectedTitle, selectedSummary, selectedExternalUrl);
	}
}

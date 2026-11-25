package com.mentalbridge.care.supportplan;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_plan_personalization_activity_mode")
class SupportPlanPersonalizationActivityModeEntity {

	@Id private UUID id;
	private UUID personalizationId;
	private short ordinal;
	private String activityMode;

	protected SupportPlanPersonalizationActivityModeEntity() { }

	SupportPlanPersonalizationActivityModeEntity(UUID personalizationId, int ordinal,
			SupportPlanPersonalizationService.ActivityMode activityMode) {
		this.id = UUID.randomUUID();
		this.personalizationId = personalizationId;
		this.ordinal = (short) ordinal;
		this.activityMode = activityMode.name();
	}

	String activityMode() { return activityMode; }
}

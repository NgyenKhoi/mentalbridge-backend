package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "support_plan_focus")
class SupportPlanFocusEntity {

	@Id private UUID id;
	private UUID supportPlanId;
	private int supportPlanModelVersion;
	private UUID userId;
	private short ordinal;
	private String title;
	private String rationale;
	@Version private long version;
	private Instant createdAt;
	private Instant updatedAt;

	protected SupportPlanFocusEntity() { }

	SupportPlanFocusEntity(UUID id, UUID supportPlanId, UUID userId, int ordinal, String title,
			String rationale, Instant now) {
		this.id = id;
		this.supportPlanId = supportPlanId;
		this.supportPlanModelVersion = 3;
		this.userId = userId;
		this.ordinal = (short) ordinal;
		this.title = title;
		this.rationale = rationale;
		this.version = 0;
		this.createdAt = now;
		this.updatedAt = now;
	}

	UUID id() { return id; }
	UUID supportPlanId() { return supportPlanId; }
	int supportPlanModelVersion() { return supportPlanModelVersion; }
	UUID userId() { return userId; }
	short ordinal() { return ordinal; }
	String title() { return title; }
	String rationale() { return rationale; }
	long version() { return version; }
	Instant createdAt() { return createdAt; }
	Instant updatedAt() { return updatedAt; }
}

package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_plan_personalization")
class SupportPlanPersonalizationEntity {

	@Id private UUID id;
	private UUID userId;
	private long revision;
	private String schemaVersion;
	private String status;
	private String preferredFocus;
	private String desiredPace;
	private Short dailyTimeBudgetMinutes;
	private String preferredDaypart;
	private Instant submittedAt;

	protected SupportPlanPersonalizationEntity() { }

	SupportPlanPersonalizationEntity(UUID id, UUID userId, long revision,
			SupportPlanPersonalizationService.PersonalizationCommand command, Instant submittedAt) {
		this.id = id;
		this.userId = userId;
		this.revision = revision;
		this.schemaVersion = SupportPlanPersonalizationService.SCHEMA_VERSION;
		this.status = command.provided() ? "PROVIDED" : "SKIPPED";
		this.preferredFocus = name(command.preferredFocus());
		this.desiredPace = name(command.desiredPace());
		this.dailyTimeBudgetMinutes = command.dailyTimeBudgetMinutes() == null
				? null : command.dailyTimeBudgetMinutes().shortValue();
		this.preferredDaypart = name(command.preferredDaypart());
		this.submittedAt = submittedAt;
	}

	private String name(Enum<?> value) {
		return value == null ? null : value.name();
	}

	UUID id() { return id; }
	UUID userId() { return userId; }
	long revision() { return revision; }
	String schemaVersion() { return schemaVersion; }
	String status() { return status; }
	String preferredFocus() { return preferredFocus; }
	String desiredPace() { return desiredPace; }
	Short dailyTimeBudgetMinutes() { return dailyTimeBudgetMinutes; }
	String preferredDaypart() { return preferredDaypart; }
	Instant submittedAt() { return submittedAt; }
}

package com.mentalbridge.consultation.availability;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "availability_slot")
class AvailabilitySlotEntity {

	@Id
	private UUID id;
	private UUID specialistAccountId;
	private Instant startAt;
	private Instant endAt;
	private String timezone;
	@Enumerated(EnumType.STRING)
	private AvailabilityModality modality;
	@Enumerated(EnumType.STRING)
	private AvailabilitySlotStatus status;
	private String idempotencyKey;
	private Instant withdrawnAt;
	private Instant createdAt;
	private Instant updatedAt;
	@Version
	private long version;

	protected AvailabilitySlotEntity() {
	}

	AvailabilitySlotEntity(UUID id, UUID specialistAccountId, Instant startAt, Instant endAt,
			String timezone, AvailabilityModality modality, String idempotencyKey, Instant now) {
		this.id = id;
		this.specialistAccountId = specialistAccountId;
		this.startAt = startAt;
		this.endAt = endAt;
		this.timezone = timezone;
		this.modality = modality;
		this.status = AvailabilitySlotStatus.ACTIVE;
		this.idempotencyKey = idempotencyKey;
		this.createdAt = now;
		this.updatedAt = now;
	}

	void withdraw(Instant now) {
		this.status = AvailabilitySlotStatus.WITHDRAWN;
		this.withdrawnAt = now;
		this.updatedAt = now;
	}

	UUID id() { return id; }
	UUID specialistAccountId() { return specialistAccountId; }
	Instant startAt() { return startAt; }
	Instant endAt() { return endAt; }
	String timezone() { return timezone; }
	AvailabilityModality modality() { return modality; }
	AvailabilitySlotStatus status() { return status; }
	String idempotencyKey() { return idempotencyKey; }
	Instant withdrawnAt() { return withdrawnAt; }
	Instant createdAt() { return createdAt; }
	Instant updatedAt() { return updatedAt; }
	long version() { return version; }
}

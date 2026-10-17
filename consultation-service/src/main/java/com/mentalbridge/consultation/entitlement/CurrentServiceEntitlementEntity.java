package com.mentalbridge.consultation.entitlement;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "current_service_entitlement")
class CurrentServiceEntitlementEntity {

	@Id
	private UUID accountId;
	@Enumerated(EnumType.STRING)
	private ServicePackage packageCode;
	@Enumerated(EnumType.STRING)
	private EntitlementSource source;
	private String sourceReference;
	private UUID establishedBy;
	private Instant effectiveFrom;
	private Instant effectiveUntil;
	private String policyVersion;
	private Instant createdAt;
	private Instant updatedAt;
	@Version
	private long version;

	protected CurrentServiceEntitlementEntity() {
	}

	UUID accountId() { return accountId; }
	ServicePackage packageCode() { return packageCode; }
	EntitlementSource source() { return source; }
	String sourceReference() { return sourceReference; }
	Instant effectiveFrom() { return effectiveFrom; }
	Instant effectiveUntil() { return effectiveUntil; }
	String policyVersion() { return policyVersion; }
	long version() { return version; }
}

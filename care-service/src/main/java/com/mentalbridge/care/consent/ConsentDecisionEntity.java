package com.mentalbridge.care.consent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "consent_decision")
class ConsentDecisionEntity {

	@Id
	@GeneratedValue
	@UuidGenerator
	private UUID id;
	private UUID userId;
	private String consentType;
	private String policyVersion;
	private boolean granted;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private Map<String, Object> evidence;
	private String idempotencyKey;
	private String requestHash;
	private Instant decidedAt;
	private Instant createdAt;

	protected ConsentDecisionEntity() {
	}

	ConsentDecisionEntity(UUID userId, String policyVersion, boolean granted, String idempotencyKey,
			String requestHash, Instant now) {
		this.userId = userId;
		this.consentType = PrivacyDisclosureService.CONSENT_TYPE;
		this.policyVersion = policyVersion;
		this.granted = granted;
		this.evidence = Map.of("channel", "CARE_UI");
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
		this.decidedAt = now;
		this.createdAt = now;
	}

	UUID id() { return id; }
	String consentType() { return consentType; }
	String policyVersion() { return policyVersion; }
	boolean granted() { return granted; }
	String requestHash() { return requestHash; }
	Instant decidedAt() { return decidedAt; }
}

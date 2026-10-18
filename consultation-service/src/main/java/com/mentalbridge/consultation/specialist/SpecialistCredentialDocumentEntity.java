package com.mentalbridge.consultation.specialist;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "specialist_credential_document")
class SpecialistCredentialDocumentEntity {

	enum Type { DEGREE, LICENSE, CERTIFICATE }
	enum Status { PENDING, VERIFIED, REJECTED }

	@Id private UUID id;
	private UUID specialistAccountId;
	private String title;
	private String issuer;
	private short issueYear;
	@Enumerated(EnumType.STRING) private Type credentialType;
	private String fileName;
	private String mediaType;
	private long fileBytes;
	private String storageKey;
	private String storageFormat;
	@Enumerated(EnumType.STRING) private Status verificationStatus;
	private boolean badgeVisible;
	private UUID reviewedBy;
	private Instant reviewedAt;
	private Instant createdAt;
	private Instant updatedAt;
	@Version private long version;

	protected SpecialistCredentialDocumentEntity() { }

	SpecialistCredentialDocumentEntity(UUID id, UUID specialistAccountId, String title, String issuer,
			int issueYear, Type credentialType, String fileName, String mediaType, long fileBytes,
			String storageKey, String storageFormat, Instant now) {
		this.id = id;
		this.specialistAccountId = specialistAccountId;
		this.title = title.strip();
		this.issuer = issuer.strip();
		this.issueYear = (short) issueYear;
		this.credentialType = credentialType;
		this.fileName = fileName;
		this.mediaType = mediaType;
		this.fileBytes = fileBytes;
		this.storageKey = storageKey;
		this.storageFormat = storageFormat;
		this.verificationStatus = Status.PENDING;
		this.createdAt = now;
		this.updatedAt = now;
	}

	void review(Status status, UUID reviewer, Instant now) {
		if (status == Status.PENDING) throw new IllegalArgumentException("Review must decide a document");
		verificationStatus = status;
		badgeVisible = status == Status.VERIFIED && badgeVisible;
		reviewedBy = reviewer;
		reviewedAt = now;
		updatedAt = now;
	}

	void showBadge(boolean visible, Instant now) {
		if (visible && verificationStatus != Status.VERIFIED) throw new IllegalStateException("Only verified documents have badges");
		badgeVisible = visible;
		updatedAt = now;
	}

	UUID id() { return id; }
	UUID specialistAccountId() { return specialistAccountId; }
	String title() { return title; }
	String issuer() { return issuer; }
	int issueYear() { return issueYear; }
	Type credentialType() { return credentialType; }
	String fileName() { return fileName; }
	String mediaType() { return mediaType; }
	long fileBytes() { return fileBytes; }
	String storageKey() { return storageKey; }
	String storageFormat() { return storageFormat; }
	Status verificationStatus() { return verificationStatus; }
	boolean badgeVisible() { return badgeVisible; }
	UUID reviewedBy() { return reviewedBy; }
	Instant reviewedAt() { return reviewedAt; }
	Instant createdAt() { return createdAt; }
	Instant updatedAt() { return updatedAt; }
	long version() { return version; }
}

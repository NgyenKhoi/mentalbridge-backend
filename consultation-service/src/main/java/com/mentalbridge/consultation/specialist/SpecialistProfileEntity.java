package com.mentalbridge.consultation.specialist;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "specialist_profile")
class SpecialistProfileEntity {

	@Id
	private UUID accountId;
	private String displayName;
	@Column(name = "biography")
	private String bio;
	@Column(name = "years_experience")
	private int yearsOfExperience;
	private String timezone;
	@Enumerated(EnumType.STRING)
	private SpecialistApprovalStatus approvalStatus;
	private Instant submittedAt;
	private Instant reviewedAt;
	private UUID reviewedBy;
	private String decisionReasonCode;
	private Instant createdAt;
	private Instant updatedAt;
	@Version
	private long version;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "specialist_profile_support_area",
			joinColumns = @JoinColumn(name = "specialist_account_id"))
	@Column(name = "support_area")
	@Enumerated(EnumType.STRING)
	private Set<SupportArea> supportAreas = new HashSet<>();

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "specialist_profile_language",
			joinColumns = @JoinColumn(name = "specialist_account_id"))
	@Column(name = "language_tag")
	private Set<String> languages = new HashSet<>();

	protected SpecialistProfileEntity() {
	}

	SpecialistProfileEntity(UUID accountId, SpecialistProfileService.ProfileCommand command, Instant now) {
		this.accountId = accountId;
		this.approvalStatus = SpecialistApprovalStatus.PENDING;
		this.createdAt = now;
		applyDraft(command, now);
	}

	void updateDraft(SpecialistProfileService.ProfileCommand command, Instant now) {
		applyDraft(command, now);
		this.submittedAt = null;
	}

	void submit(Instant now) {
		if (submittedAt == null) {
			this.submittedAt = now;
			this.updatedAt = now;
		}
	}

	void approve(UUID adminAccountId, Instant now) {
		this.approvalStatus = SpecialistApprovalStatus.APPROVED;
		this.reviewedAt = now;
		this.reviewedBy = adminAccountId;
		this.decisionReasonCode = null;
		this.updatedAt = now;
	}

	private void applyDraft(SpecialistProfileService.ProfileCommand command, Instant now) {
		this.displayName = command.displayName().strip();
		this.bio = command.bio().strip();
		this.yearsOfExperience = command.yearsOfExperience();
		this.timezone = command.timezone().strip();
		this.supportAreas.clear();
		this.supportAreas.addAll(command.supportAreas());
		this.languages.clear();
		command.languages().stream().map(String::strip).map(String::toLowerCase).forEach(this.languages::add);
		this.updatedAt = now;
	}

	UUID accountId() { return accountId; }
	String displayName() { return displayName; }
	String bio() { return bio; }
	int yearsOfExperience() { return yearsOfExperience; }
	String timezone() { return timezone; }
	SpecialistApprovalStatus approvalStatus() { return approvalStatus; }
	Instant submittedAt() { return submittedAt; }
	Instant reviewedAt() { return reviewedAt; }
	UUID reviewedBy() { return reviewedBy; }
	String decisionReasonCode() { return decisionReasonCode; }
	Instant createdAt() { return createdAt; }
	Instant updatedAt() { return updatedAt; }
	long version() { return version; }
	Set<SupportArea> supportAreas() { return Set.copyOf(supportAreas); }
	Set<String> languages() { return Set.copyOf(languages); }
}

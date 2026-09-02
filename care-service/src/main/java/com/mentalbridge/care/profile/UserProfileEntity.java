package com.mentalbridge.care.profile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "user_profile")
public class UserProfileEntity {

	@Id
	private UUID accountId;
	private String displayName;
	private LocalDate dateOfBirth;
	private String gender;
	private String locale;
	private String timezone;
	private boolean reminderEnabled;
	private Instant createdAt;
	private Instant updatedAt;
	@Version
	private long version;

	protected UserProfileEntity() {
	}

	UserProfileEntity(UUID accountId, ProfileService.ProfileCommand command, Instant now) {
		this.accountId = accountId;
		this.createdAt = now;
		update(command, now);
	}

	void update(ProfileService.ProfileCommand command, Instant now) {
		this.displayName = command.displayName().strip();
		this.dateOfBirth = command.dateOfBirth();
		var normalizedGender = command.gender() == null ? null : command.gender().strip();
		this.gender = normalizedGender == null || normalizedGender.isEmpty() ? null : normalizedGender;
		this.locale = command.locale().strip();
		this.timezone = command.timezone().strip();
		this.reminderEnabled = command.reminderEnabled();
		this.updatedAt = now;
	}

	UUID accountId() { return accountId; }
	String displayName() { return displayName; }
	LocalDate dateOfBirth() { return dateOfBirth; }
	String gender() { return gender; }
	String locale() { return locale; }
	String timezone() { return timezone; }
	boolean reminderEnabled() { return reminderEnabled; }
	Instant createdAt() { return createdAt; }
	Instant updatedAt() { return updatedAt; }
	long version() { return version; }
}

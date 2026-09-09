package com.mentalbridge.care.profile;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.shared.ApiException;

@Service
public class ProfileService {
	static final String DEFAULT_LOCALE = "vi-VN";
	static final String DEFAULT_TIMEZONE = "Asia/Ho_Chi_Minh";
	static final boolean DEFAULT_REMINDER_ENABLED = false;
	private static final int MINIMUM_AGE = 18;

	private final UserProfileRepository profiles;
	private final Clock clock;

	public ProfileService(UserProfileRepository profiles, Clock clock) {
		this.profiles = profiles;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ProfileView get(UUID accountId) {
		return view(profiles.findById(accountId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"PROFILE_NOT_FOUND", "Care profile was not found")));
	}

	@Transactional
	public SavedProfile put(UUID accountId, Long expectedVersion, ProfileCommand command) {
		validateDateOfBirth(command.dateOfBirth());
		var existing = profiles.findByIdForUpdate(accountId);
		if (existing.isEmpty()) {
			if (expectedVersion != null) {
				throw new ApiException(HttpStatus.PRECONDITION_FAILED, "PROFILE_VERSION_MISMATCH",
						"A new profile must not include If-Match");
			}
			return new SavedProfile(view(profiles.saveAndFlush(new UserProfileEntity(accountId, command, clock.instant()))), true);
		}
		var profile = existing.orElseThrow();
		if (expectedVersion == null) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "PROFILE_VERSION_REQUIRED",
					"If-Match is required when replacing an existing profile");
		}
		if (profile.version() != expectedVersion) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "PROFILE_VERSION_MISMATCH",
					"Care profile has changed since it was read");
		}
		profile.update(command, clock.instant());
		return new SavedProfile(view(profiles.saveAndFlush(profile)), false);
	}

	private void validateDateOfBirth(LocalDate dateOfBirth) {
		if (dateOfBirth == null) return;
		var today = LocalDate.now(clock);
		if (dateOfBirth.isAfter(today)) {
			throw validation("DATE_OF_BIRTH_IN_FUTURE", "Date of birth cannot be in the future");
		}
		if (dateOfBirth.plusYears(MINIMUM_AGE).isAfter(today)) {
			throw validation("MINIMUM_AGE_NOT_MET", "The user must be at least 18 years old");
		}
	}

	private ApiException validation(String code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Profile date of birth is invalid",
				java.util.List.of(new ApiException.FieldViolation("dateOfBirth", code, message)));
	}

	private ProfileView view(UserProfileEntity profile) {
		return new ProfileView(profile.accountId(), profile.displayName(), profile.dateOfBirth(), profile.gender(),
				profile.locale(), profile.timezone(), profile.reminderEnabled(), profile.createdAt(), profile.updatedAt(),
				profile.version());
	}

	public record ProfileCommand(String displayName, LocalDate dateOfBirth, String gender, String locale,
			String timezone, boolean reminderEnabled) {
	}

	public record ProfileView(UUID accountId, String displayName, LocalDate dateOfBirth, String gender, String locale,
			String timezone, boolean reminderEnabled, java.time.Instant createdAt, java.time.Instant updatedAt,
			long version) {
	}

	public record SavedProfile(ProfileView profile, boolean created) {
	}
}

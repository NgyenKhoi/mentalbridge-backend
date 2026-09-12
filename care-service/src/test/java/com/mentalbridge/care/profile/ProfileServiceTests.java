package com.mentalbridge.care.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.shared.ApiException;

class ProfileServiceTests {

	private static final Instant NOW = Instant.parse("2026-03-01T00:00:00Z");
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);
	private final ProfileService service = new ProfileService(profiles, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void acceptsTheExactEighteenthBirthdayAndDoesNotInferAMaximumAge() {
		var exactBoundary = UUID.randomUUID();
		when(profiles.findByIdForUpdate(exactBoundary)).thenReturn(Optional.empty());
		when(profiles.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

		var saved = service.put(exactBoundary, null, command(LocalDate.of(2008, 3, 1)));
		assertThat(saved.profile().dateOfBirth()).isEqualTo(LocalDate.of(2008, 3, 1));

		var oldDate = UUID.randomUUID();
		when(profiles.findByIdForUpdate(oldDate)).thenReturn(Optional.empty());
		assertThat(service.put(oldDate, null, command(LocalDate.of(1900, 1, 1))).profile().dateOfBirth())
				.isEqualTo(LocalDate.of(1900, 1, 1));
	}

	@Test
	void rejectsFutureAndUnderageDatesWithFieldViolations() {
		assertViolation(LocalDate.of(2026, 3, 2), "DATE_OF_BIRTH_IN_FUTURE");
		assertViolation(LocalDate.of(2008, 3, 2), "MINIMUM_AGE_NOT_MET");
	}

	@Test
	void treatsLeapDayAsReachingTheBoundaryOnFebruaryTwentyEight() {
		var leapBoundaryService = new ProfileService(profiles,
				Clock.fixed(Instant.parse("2026-02-28T00:00:00Z"), ZoneOffset.UTC));
		var leapDayUser = UUID.randomUUID();
		when(profiles.findByIdForUpdate(leapDayUser)).thenReturn(Optional.empty());
		when(profiles.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

		assertThat(leapBoundaryService.put(leapDayUser, null, command(LocalDate.of(2008, 2, 29)))
				.profile().dateOfBirth()).isEqualTo(LocalDate.of(2008, 2, 29));
	}

	private void assertViolation(LocalDate dateOfBirth, String code) {
		assertThatThrownBy(() -> service.put(UUID.randomUUID(), null, command(dateOfBirth)))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.code()).isEqualTo("VALIDATION_FAILED");
					assertThat(exception.violations()).singleElement().satisfies(violation -> {
						assertThat(violation.field()).isEqualTo("dateOfBirth");
						assertThat(violation.code()).isEqualTo(code);
					});
				});
	}

	private ProfileService.ProfileCommand command(LocalDate dateOfBirth) {
		return new ProfileService.ProfileCommand("Nguyễn An", dateOfBirth, null, ProfileService.DEFAULT_LOCALE,
				ProfileService.DEFAULT_TIMEZONE, ProfileService.DEFAULT_REMINDER_ENABLED);
	}
}

package com.mentalbridge.care.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class AnonymousAssessmentSessionEntityTests {

	private static final Instant CREATED_AT = Instant.parse("2026-09-02T00:00:00Z");

	@Test
	void validActivitySlidesInactivityDeadline() {
		var session = new AnonymousAssessmentSessionEntity("hash", CREATED_AT, CREATED_AT.plus(Duration.ofMinutes(30)));
		session.recordActivity(CREATED_AT.plus(Duration.ofMinutes(20)), Duration.ofMinutes(30), Duration.ofHours(2));

		assertThat(session.expiresAt()).isEqualTo(CREATED_AT.plus(Duration.ofMinutes(50)));
	}

	@Test
	void activityNeverExtendsPastAbsoluteLifetime() {
		var session = new AnonymousAssessmentSessionEntity("hash", CREATED_AT, CREATED_AT.plus(Duration.ofMinutes(30)));
		session.recordActivity(CREATED_AT.plus(Duration.ofMinutes(110)), Duration.ofMinutes(30), Duration.ofHours(2));

		assertThat(session.expiresAt()).isEqualTo(CREATED_AT.plus(Duration.ofHours(2)));
		assertThat(session.unavailableAt(CREATED_AT.plus(Duration.ofHours(2)))).isTrue();
	}
}

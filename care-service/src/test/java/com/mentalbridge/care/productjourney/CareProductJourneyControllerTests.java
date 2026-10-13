package com.mentalbridge.care.productjourney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.screeningepisode.ScreeningEpisodeRepository;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportguide.SupportGuideRepository;
import com.mentalbridge.care.supportplan.SupportPlanRepository;

class CareProductJourneyControllerTests {

	@Test
	void returnsOnlyBoundedAggregateCounts() {
		Instant from = Instant.parse("2026-09-09T08:00:00Z");
		Instant to = Instant.parse("2026-10-09T08:00:00Z");
		var screeningEpisodes = mock(ScreeningEpisodeRepository.class);
		var guides = mock(SupportGuideRepository.class);
		var plans = mock(SupportPlanRepository.class);
		when(screeningEpisodes.countByStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan("COMPLETED", from, to))
				.thenReturn(31L);
		when(guides.countByGeneratedAtGreaterThanEqualAndGeneratedAtLessThan(from, to)).thenReturn(29L);
		when(plans.countPaidActivationsBetween(from, to)).thenReturn(7L);

		var response = new CareProductJourneyController(screeningEpisodes, guides, plans,
				Clock.fixed(to, ZoneOffset.UTC)).metrics(from.toString(), to.toString());

		assertThat(response).extracting("source", "sourceVersion", "completedScreeningEpisodes",
				"supportGuidesGenerated", "paidSupportPlansActivated")
				.containsExactly("CARE", "care-product-journey-v1", 31L, 29L, 7L);
	}

	@Test
	void rejectsInvalidWindowsBeforeQueryingOwnerData() {
		Instant now = Instant.parse("2026-10-09T08:00:00Z");
		var controller = new CareProductJourneyController(mock(ScreeningEpisodeRepository.class),
				mock(SupportGuideRepository.class), mock(SupportPlanRepository.class), Clock.fixed(now, ZoneOffset.UTC));
		assertThatThrownBy(() -> controller.metrics(now.toString(), now.toString()))
				.isInstanceOf(ApiException.class);
	}
}

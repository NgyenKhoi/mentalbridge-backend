package com.mentalbridge.care.productjourney;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.screeningepisode.ScreeningEpisodeRepository;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportguide.SupportGuideRepository;
import com.mentalbridge.care.supportplan.SupportPlanRepository;

@RestController
@RequestMapping("/api/v1/admin/product-journey-metrics")
@PreAuthorize("hasRole('ADMIN')")
public class CareProductJourneyController {

	private static final Duration MAXIMUM_WINDOW = Duration.ofDays(366);
	private final ScreeningEpisodeRepository screeningEpisodes;
	private final SupportGuideRepository guides;
	private final SupportPlanRepository plans;
	private final Clock clock;

	public CareProductJourneyController(ScreeningEpisodeRepository screeningEpisodes,
			SupportGuideRepository guides, SupportPlanRepository plans, Clock clock) {
		this.screeningEpisodes = screeningEpisodes;
		this.guides = guides;
		this.plans = plans;
		this.clock = clock;
	}

	@GetMapping
	public Response metrics(@RequestParam String from, @RequestParam String to) {
		Instant start = instant(from);
		Instant end = instant(to);
		validate(start, end);
		Instant asOf = clock.instant();
		return new Response("CARE", "care-product-journey-v1", asOf,
				screeningEpisodes.countByStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
						"COMPLETED", start, end),
				guides.countByGeneratedAtGreaterThanEqualAndGeneratedAtLessThan(start, end),
				plans.countPaidActivationsBetween(start, end));
	}

	private Instant instant(String value) {
		try {
			return Instant.parse(value);
		}
		catch (DateTimeParseException exception) {
			throw invalid();
		}
	}

	private void validate(Instant from, Instant to) {
		if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAXIMUM_WINDOW) > 0
				|| to.isAfter(clock.instant().plusSeconds(30))) throw invalid();
	}

	private ApiException invalid() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_JOURNEY_WINDOW",
				"from and to must define a completed UTC window of at most 366 days");
	}

	public record Response(String source, String sourceVersion, Instant asOf,
			long completedScreeningEpisodes, long supportGuidesGenerated, long paidSupportPlansActivated) { }
}

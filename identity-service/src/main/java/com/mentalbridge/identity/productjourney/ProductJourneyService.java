package com.mentalbridge.identity.productjourney;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.mentalbridge.identity.account.AccountRepository;

@Service
public class ProductJourneyService {

	static final String PROJECTION_VERSION = "product-journey-metrics-v1";
	private static final Duration MAXIMUM_WINDOW = Duration.ofDays(366);
	private final AccountRepository accounts;
	private final ProductJourneySourceClient sources;
	private final Clock clock;

	public ProductJourneyService(AccountRepository accounts, ProductJourneySourceClient sources, Clock clock) {
		this.accounts = accounts;
		this.sources = sources;
		this.clock = clock;
	}

	public Response metrics(Instant from, Instant to, String bearerToken) {
		validate(from, to, bearerToken);
		Instant identityAsOf = clock.instant();
		long registered = accounts.countRegisteredBetween(from, to);
		long active = accounts.countCurrentlyActiveRegisteredBetween(from, to);
		var sourceStates = new ArrayList<SourceState>();
		var stages = new ArrayList<Stage>();
		sourceStates.add(SourceState.available("IDENTITY", "identity-account-projection-v1", identityAsOf));
		stages.add(Stage.available("REGISTERED_ACCOUNTS", "IDENTITY", registered, null));
		stages.add(Stage.available("ACTIVE_REGISTERED_ACCOUNTS", "IDENTITY", active,
				rate(active, registered, "REGISTERED_ACCOUNTS")));

		try {
			var care = sources.care(from, to, bearerToken);
			if (!validCare(care)) throw new IllegalStateException("Invalid Care aggregate response");
			sourceStates.add(SourceState.available("CARE", care.sourceVersion(), care.asOf()));
			stages.add(Stage.available("COMPLETED_SCREENING_EPISODES", "CARE", care.completedScreeningEpisodes(), null));
			stages.add(Stage.available("SUPPORT_GUIDES_GENERATED", "CARE", care.supportGuidesGenerated(), null));
			stages.add(Stage.unavailable("SUPPORT_GUIDES_OPENED", "CARE", "AUTHORITATIVE_USAGE_FACT_UNAVAILABLE"));
			stages.add(Stage.available("PAID_SUPPORT_PLANS_ACTIVATED", "CARE", care.paidSupportPlansActivated(), null));
		}
		catch (RuntimeException exception) {
			sourceStates.add(SourceState.unavailable("CARE"));
			stages.add(Stage.unavailable("COMPLETED_SCREENING_EPISODES", "CARE", "SOURCE_UNAVAILABLE"));
			stages.add(Stage.unavailable("SUPPORT_GUIDES_GENERATED", "CARE", "SOURCE_UNAVAILABLE"));
			stages.add(Stage.unavailable("SUPPORT_GUIDES_OPENED", "CARE", "AUTHORITATIVE_USAGE_FACT_UNAVAILABLE"));
			stages.add(Stage.unavailable("PAID_SUPPORT_PLANS_ACTIVATED", "CARE", "SOURCE_UNAVAILABLE"));
		}

		try {
			var consultation = sources.consultation(from, to, bearerToken);
			if (!validConsultation(consultation)) throw new IllegalStateException("Invalid Consultation aggregate response");
			sourceStates.add(SourceState.available("CONSULTATION", consultation.sourceVersion(), consultation.asOf()));
			long requested = consultation.consultationsRequested();
			stages.add(Stage.available("CONSULTATIONS_REQUESTED", "CONSULTATION", requested, null));
			stages.add(Stage.available("CONSULTATIONS_CONFIRMED", "CONSULTATION", consultation.consultationsConfirmed(),
					rate(consultation.consultationsConfirmed(), requested, "CONSULTATIONS_REQUESTED")));
			stages.add(Stage.available("CONSULTATIONS_COMPLETED", "CONSULTATION", consultation.consultationsCompleted(),
					rate(consultation.consultationsCompleted(), requested, "CONSULTATIONS_REQUESTED")));
		}
		catch (RuntimeException exception) {
			sourceStates.add(SourceState.unavailable("CONSULTATION"));
			stages.add(Stage.unavailable("CONSULTATIONS_REQUESTED", "CONSULTATION", "SOURCE_UNAVAILABLE"));
			stages.add(Stage.unavailable("CONSULTATIONS_CONFIRMED", "CONSULTATION", "SOURCE_UNAVAILABLE"));
			stages.add(Stage.unavailable("CONSULTATIONS_COMPLETED", "CONSULTATION", "SOURCE_UNAVAILABLE"));
		}

		return new Response(PROJECTION_VERSION, new Window(from, to), clock.instant(),
				"DESCRIPTIVE_PRODUCT_ACTIVITY_NOT_CLINICAL_EFFECTIVENESS", List.copyOf(sourceStates), List.copyOf(stages));
	}

	private boolean validCare(ProductJourneySourceClient.CareMetrics value) {
		return value != null && "CARE".equals(value.source()) && "care-product-journey-v1".equals(value.sourceVersion())
				&& value.asOf() != null && value.completedScreeningEpisodes() >= 0 && value.supportGuidesGenerated() >= 0
				&& value.paidSupportPlansActivated() >= 0;
	}

	private boolean validConsultation(ProductJourneySourceClient.ConsultationMetrics value) {
		return value != null && "CONSULTATION".equals(value.source())
				&& "consultation-product-journey-v1".equals(value.sourceVersion()) && value.asOf() != null
				&& value.consultationsRequested() >= 0 && value.consultationsConfirmed() >= 0
				&& value.consultationsCompleted() >= 0 && value.consultationsConfirmed() <= value.consultationsRequested()
				&& value.consultationsCompleted() <= value.consultationsRequested();
	}

	private Rate rate(long numerator, long denominator, String denominatorStage) {
		if (denominator == 0) return null;
		return new Rate(denominatorStage, BigDecimal.valueOf(numerator * 100.0 / denominator)
				.setScale(1, RoundingMode.HALF_UP));
	}

	private void validate(Instant from, Instant to, String bearerToken) {
		if (from == null || to == null || bearerToken == null || bearerToken.isBlank() || !from.isBefore(to)
				|| Duration.between(from, to).compareTo(MAXIMUM_WINDOW) > 0
				|| to.isAfter(clock.instant().plusSeconds(30))) throw new InvalidWindowException();
	}

	public record Response(String projectionVersion, Window window, Instant asOf, String interpretation,
			List<SourceState> sources, List<Stage> stages) { }
	public record Window(Instant from, Instant to) { }
	public record SourceState(String source, String sourceVersion, String status, Instant asOf, String unavailableReason) {
		static SourceState available(String source, String version, Instant asOf) {
			return new SourceState(source, version, "AVAILABLE", asOf, null);
		}
		static SourceState unavailable(String source) {
			return new SourceState(source, null, "UNAVAILABLE", null, "DEPENDENCY_UNAVAILABLE");
		}
	}
	public record Stage(String stage, String source, String status, Long count, Rate rate, String unavailableReason) {
		static Stage available(String stage, String source, long count, Rate rate) {
			return new Stage(stage, source, "AVAILABLE", count, rate, null);
		}
		static Stage unavailable(String stage, String source, String reason) {
			return new Stage(stage, source, "UNAVAILABLE", null, null, reason);
		}
	}
	public record Rate(String denominatorStage, BigDecimal percentage) { }
	static final class InvalidWindowException extends RuntimeException { }
}

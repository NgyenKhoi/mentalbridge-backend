package com.mentalbridge.identity.productjourney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.mentalbridge.identity.account.AccountRepository;

class ProductJourneyServiceTests {

	private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");
	private static final Instant FROM = Instant.parse("2026-09-09T08:00:00Z");

	@Test
	void composesOnlyAggregateOwnerFactsAndCohortRates() {
		var accounts = mock(AccountRepository.class);
		var sources = mock(ProductJourneySourceClient.class);
		when(accounts.countRegisteredBetween(FROM, NOW)).thenReturn(100L);
		when(accounts.countCurrentlyActiveRegisteredBetween(FROM, NOW)).thenReturn(80L);
		when(sources.care(FROM, NOW, "token")).thenReturn(new ProductJourneySourceClient.CareMetrics(
				"CARE", "care-product-journey-v1", NOW, 70, 60, 20));
		when(sources.consultation(FROM, NOW, "token")).thenReturn(
				new ProductJourneySourceClient.ConsultationMetrics("CONSULTATION",
						"consultation-product-journey-v1", NOW, 10, 8, 6));

		var response = new ProductJourneyService(accounts, sources, Clock.fixed(NOW, ZoneOffset.UTC))
				.metrics(FROM, NOW, "token");

		assertThat(response.projectionVersion()).isEqualTo("product-journey-metrics-v1");
		assertThat(response.interpretation()).isEqualTo("DESCRIPTIVE_PRODUCT_ACTIVITY_NOT_CLINICAL_EFFECTIVENESS");
		assertThat(response.sources()).allMatch(source -> source.status().equals("AVAILABLE"));
		assertThat(response.stages()).hasSize(9);
		assertThat(response.stages()).filteredOn(stage -> stage.stage().equals("ACTIVE_REGISTERED_ACCOUNTS"))
				.singleElement().extracting(stage -> stage.rate().percentage().toPlainString()).isEqualTo("80.0");
		assertThat(response.stages()).filteredOn(stage -> stage.stage().equals("SUPPORT_GUIDES_OPENED"))
				.singleElement().satisfies(stage -> {
					assertThat(stage.status()).isEqualTo("UNAVAILABLE");
					assertThat(stage.count()).isNull();
					assertThat(stage.unavailableReason()).isEqualTo("AUTHORITATIVE_USAGE_FACT_UNAVAILABLE");
				});
	}

	@Test
	void marksAnUnavailableOwnerWithoutInventingZeroes() {
		var accounts = mock(AccountRepository.class);
		var sources = mock(ProductJourneySourceClient.class);
		when(sources.care(FROM, NOW, "token")).thenThrow(new IllegalStateException("unavailable"));
		when(sources.consultation(FROM, NOW, "token")).thenThrow(new IllegalStateException("unavailable"));

		var response = new ProductJourneyService(accounts, sources, Clock.fixed(NOW, ZoneOffset.UTC))
				.metrics(FROM, NOW, "token");

		assertThat(response.sources()).filteredOn(source -> !source.source().equals("IDENTITY"))
				.allMatch(source -> source.status().equals("UNAVAILABLE") && source.asOf() == null);
		assertThat(response.stages()).filteredOn(stage -> !stage.source().equals("IDENTITY"))
				.allMatch(stage -> stage.count() == null);
	}

	@Test
	void rejectsUnboundedOrFutureWindows() {
		var service = new ProductJourneyService(mock(AccountRepository.class), mock(ProductJourneySourceClient.class),
				Clock.fixed(NOW, ZoneOffset.UTC));
		assertThatThrownBy(() -> service.metrics(NOW.minusSeconds(367L * 86_400), NOW, "token"))
				.isInstanceOf(ProductJourneyService.InvalidWindowException.class);
		assertThatThrownBy(() -> service.metrics(FROM, NOW.plusSeconds(31), "token"))
				.isInstanceOf(ProductJourneyService.InvalidWindowException.class);
	}
}

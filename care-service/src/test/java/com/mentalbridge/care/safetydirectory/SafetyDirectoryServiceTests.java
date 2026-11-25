package com.mentalbridge.care.safetydirectory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.safetydirectory.SafetyDirectoryContract.LookupRequest;
import com.mentalbridge.care.safetydirectory.SafetyDirectoryContract.ProviderResponse;
import com.mentalbridge.care.safetydirectory.SafetyDirectoryContract.State;
import com.mentalbridge.care.safetydirectory.SafetyDirectoryContract.Trigger;
import com.mentalbridge.care.shared.ApiException;

class SafetyDirectoryServiceTests {
	private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");
	private final ContentSafetyDirectoryHttpClient client = mock(ContentSafetyDirectoryHttpClient.class);
	private final SafetyDirectoryService service = new SafetyDirectoryService(client, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void returnsCurrentProviderResultsWithReviewedSafetyCopy() {
		var current = new SafetyDirectoryContract.Entry(
				"123e4567-e89b-42d3-a456-426614174000", "Synthetic facility", "FACILITY",
				"not-dialable", "Synthetic address", List.of(new Object()), "Synthetic source",
				"controlled-test-reference", "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z");
		when(client.lookup(any(), any())).thenReturn(new ProviderResponse("RESULTS", "ignored", List.of(current)));
		var result = service.lookup(new LookupRequest(Trigger.HELP_NOW, "79", null, null), "correlation");
		assertThat(result.state()).isEqualTo(State.RESULTS);
		assertThat(result.entries()).containsExactly(current);
		assertThat(result.areaWording()).isEqualTo("Cơ sở trong khu vực đã chọn");
		assertThat(result.safetyGuidance()).contains("dịch vụ khẩn cấp");
		assertThat(result.limitation()).contains("không tự động liên hệ bên thứ ba");
	}

	@Test
	void returnsSynchronousFallbackWhenDirectoryIsUnavailable() {
		when(client.lookup(any(), any())).thenThrow(new RuntimeException("timeout"));
		var result = service.lookup(new LookupRequest(Trigger.POSITIVE_ITEM_9, null, null, "Hà Nội"), "correlation");
		assertThat(result.state()).isEqualTo(State.UNAVAILABLE);
		assertThat(result.entries()).isEmpty();
		assertThat(result.safetyGuidance()).isNotBlank();
	}

	@Test
	void failsClosedWhenProviderReturnsMalformedCurrentEntry() {
		var malformed = new SafetyDirectoryContract.Entry(
				"entry", "Synthetic facility", "FACILITY", "not-dialable", "Synthetic address",
				List.of(), "Synthetic source", "controlled-test-reference", "not-a-timestamp",
				"2026-09-01T00:00:00Z");
		when(client.lookup(any(), any())).thenReturn(new ProviderResponse("RESULTS", "ignored", List.of(malformed)));

		var result = service.lookup(new LookupRequest(Trigger.HELP_NOW, "79", null, null), "correlation");

		assertThat(result.state()).isEqualTo(State.UNAVAILABLE);
		assertThat(result.entries()).isEmpty();
		assertThat(result.safetyGuidance()).isNotBlank();
	}

	@Test
	void rejectsStaleEntriesAtTheNinetyDayBoundary() {
		var stale = new SafetyDirectoryContract.Entry(
				"123e4567-e89b-42d3-a456-426614174000", "Synthetic facility", "FACILITY",
				"not-dialable", "Synthetic address", List.of(new Object()), "Synthetic source",
				"controlled-test-reference", "2026-06-20T00:00:00Z", "2026-06-20T00:00:00Z");
		when(client.lookup(any(), any())).thenReturn(new ProviderResponse("RESULTS", "ignored", List.of(stale)));

		var result = service.lookup(new LookupRequest(Trigger.HELP_NOW, "79", null, null), "correlation");

		assertThat(result.state()).isEqualTo(State.UNAVAILABLE);
		assertThat(result.entries()).isEmpty();
	}

	@Test
	void rejectsMissingOrAmbiguousManualAreaInput() {
		assertThatThrownBy(() -> service.lookup(new LookupRequest(Trigger.HELP_NOW, "79", null, "Hà Nội"), "c"))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void controllerMarksSafetyResponsesNoStore() {
		when(client.lookup(any(), any())).thenReturn(new ProviderResponse("EMPTY", "ignored", List.of()));
		var response = new SafetyDirectoryController(service)
				.lookup(new LookupRequest(Trigger.HELP_NOW, "79", null, null), null);
		assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
	}
}

package com.mentalbridge.care.safetydirectory;

import java.util.List;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.safetydirectory.SafetyDirectoryContract.LookupRequest;
import com.mentalbridge.care.safetydirectory.SafetyDirectoryContract.LookupResponse;
import com.mentalbridge.care.safetydirectory.SafetyDirectoryContract.ProviderRequest;
import com.mentalbridge.care.safetydirectory.SafetyDirectoryContract.State;
import com.mentalbridge.care.shared.ApiException;

@Service
public class SafetyDirectoryService {
	static final String AREA_WORDING = "Cơ sở trong khu vực đã chọn";
	static final String SAFETY_GUIDANCE = "Nếu bạn cảm thấy mình không an toàn hoặc có nguy cơ gây hại cho bản thân, hãy chủ động liên hệ dịch vụ khẩn cấp hoặc cơ sở y tế phù hợp tại khu vực của bạn.";
	static final String LIMITATION = "MentalBridge không cung cấp dịch vụ ứng cứu khẩn cấp, không giám sát con người 24/7 và không tự động liên hệ bên thứ ba.";

	private final ContentSafetyDirectoryHttpClient client;
	private final Clock clock;

	public SafetyDirectoryService(ContentSafetyDirectoryHttpClient client, Clock clock) {
		this.client = client;
		this.clock = clock;
	}

	public LookupResponse lookup(LookupRequest request, String correlationId) {
		validate(request);
		try {
			var provider = client.lookup(correlationId,
					new ProviderRequest(trim(request.provinceCode()), trim(request.districtCode()), trim(request.manualLocation())));
			var state = providerState(provider);
			List<SafetyDirectoryContract.Entry> entries = state == State.RESULTS
					? validatedEntries(provider.entries()) : List.of();
			if (state == State.RESULTS && entries.isEmpty()) state = State.UNAVAILABLE;
			return response(request, state, entries);
		}
		catch (RuntimeException ignored) {
			return response(request, State.UNAVAILABLE, List.of());
		}
	}

	private State providerState(SafetyDirectoryContract.ProviderResponse provider) {
		if (provider == null || provider.state() == null) return State.UNAVAILABLE;
		return switch (provider.state()) {
			case "RESULTS", "EMPTY", "INVALID_AREA" -> State.valueOf(provider.state());
			default -> State.UNAVAILABLE;
		};
	}

	private List<SafetyDirectoryContract.Entry> validatedEntries(List<SafetyDirectoryContract.Entry> entries) {
		if (entries == null || entries.isEmpty() || entries.size() > 100
				|| entries.stream().anyMatch(entry -> !isCurrentEntry(entry))) {
			throw new IllegalArgumentException("Content returned an unsafe directory response");
		}
		return List.copyOf(entries);
	}

	private boolean isCurrentEntry(SafetyDirectoryContract.Entry entry) {
		if (entry == null || !validUuid(entry.directoryEntryId()) || invalidLength(entry.name(), 200)
				|| blank(entry.type())
				|| blank(entry.phone()) || blank(entry.sourceName()) || blank(entry.sourceReference())
				|| entry.phone().length() > 64 || entry.sourceName().length() > 200
				|| entry.sourceReference().length() > 2000 || blank(entry.reviewedAt())
				|| blank(entry.verifiedAt()) || entry.coverage() == null || entry.coverage().isEmpty()
				|| entry.coverage().size() > 100) {
			return false;
		}
		try {
			var reviewedAt = Instant.parse(entry.reviewedAt());
			var verifiedAt = Instant.parse(entry.verifiedAt());
			var now = clock.instant();
			boolean knownType = entry.type().equals("FACILITY") || entry.type().equals("HOTLINE");
			boolean facilityHasAddress = !entry.type().equals("FACILITY")
					|| (present(entry.address()) && entry.address().trim().length() <= 500);
			return knownType && facilityHasAddress && !reviewedAt.isAfter(now) && !verifiedAt.isAfter(now)
					&& now.isBefore(verifiedAt.plus(Duration.ofDays(90)));
		}
		catch (RuntimeException exception) {
			return false;
		}
	}

	private void validate(LookupRequest request) {
		if (request == null || request.trigger() == null) invalid("trigger");
		boolean selected = present(request.provinceCode());
		boolean manual = present(request.manualLocation());
		if (selected == manual || (present(request.districtCode()) && !selected)) invalid("location");
		if (length(request.provinceCode()) > 32 || length(request.districtCode()) > 32 || length(request.manualLocation()) > 120) invalid("location");
	}

	private void invalid(String field) {
		throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid safety directory lookup",
				List.of(new ApiException.FieldViolation(field, "INVALID_LOCATION", "Select one supported area input")));
	}

	private LookupResponse response(LookupRequest request, State state, List<SafetyDirectoryContract.Entry> entries) {
		return new LookupResponse(request.trigger(), state, AREA_WORDING, SAFETY_GUIDANCE, LIMITATION, entries);
	}

	private static boolean present(String value) { return value != null && !value.isBlank(); }
	private static boolean blank(String value) { return value == null || value.isBlank(); }
	private static boolean invalidLength(String value, int maximum) {
		return blank(value) || value.trim().length() > maximum;
	}
	private static boolean validUuid(String value) {
		try {
			UUID.fromString(value);
			return true;
		}
		catch (RuntimeException exception) {
			return false;
		}
	}
	private static int length(String value) { return value == null ? 0 : value.trim().length(); }
	private static String trim(String value) { return value == null ? null : value.trim(); }
}

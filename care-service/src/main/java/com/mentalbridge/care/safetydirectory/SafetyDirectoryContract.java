package com.mentalbridge.care.safetydirectory;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public final class SafetyDirectoryContract {
	private SafetyDirectoryContract() {}

	public enum Trigger { POSITIVE_ITEM_9, HELP_NOW }
	public enum State { RESULTS, EMPTY, INVALID_AREA, UNAVAILABLE }

	public record LookupRequest(Trigger trigger, String provinceCode, String districtCode, String manualLocation) {}
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ProviderRequest(String provinceCode, String districtCode, String manualLocation) {}
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Entry(String directoryEntryId, String name, String type, String phone, String address,
			List<Object> coverage, String sourceName, String sourceReference, String reviewedAt, String verifiedAt) {}
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ProviderResponse(String state, String wording, List<Entry> entries) {}
	public record LookupResponse(Trigger trigger, State state, String areaWording, String safetyGuidance,
			String limitation, List<Entry> entries) {}
}

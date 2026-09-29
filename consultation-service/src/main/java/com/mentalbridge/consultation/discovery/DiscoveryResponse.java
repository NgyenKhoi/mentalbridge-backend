package com.mentalbridge.consultation.discovery;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mentalbridge.consultation.appointment.AppointmentModality;
import com.mentalbridge.consultation.entitlement.ServicePackage;
import com.mentalbridge.consultation.specialist.SupportArea;

public final class DiscoveryResponse {

	private DiscoveryResponse() { }

	public enum ContextState { NOT_REQUESTED, APPLIED, UNAVAILABLE }
	public enum BookingHandoff { BROWSE_ONLY, BOOKING_POLICY_CHECK_REQUIRED }
	public enum Compatibility { NEUTRAL, MATCHED, NOT_MATCHED, UNAVAILABLE }
	public enum TimezoneMatch { NOT_REQUESTED, EXACT, OFFSET_DISTANCE }

	public record Page(List<Item> items, int count, String nextCursor, String rankingPolicyVersion,
			Instant generatedAt, ContextState contextState, ServicePackage packageCode,
			BookingHandoff bookingHandoff, boolean videoEnabled) { }

	public record Item(UUID specialistAccountId, String displayName, String bio, Set<SupportArea> supportAreas,
			Set<String> languages, int yearsOfExperience, String timezone, Explanation explanation,
			List<Slot> selectableSlots) { }

	public record Explanation(Compatibility compatibility, Boolean languageMatched, boolean hasSelectableSlot,
			Instant earliestSelectableStartAt, TimezoneMatch timezoneMatch, Integer timezoneOffsetDistanceMinutes,
			boolean ratingTieBreakerApplied, List<String> codes) { }

	public record Slot(UUID id, UUID specialistAccountId, Instant startAt, Instant endAt, String timezone,
			AppointmentModality modality, long version) { }
}

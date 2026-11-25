package com.mentalbridge.consultation.availability;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AvailabilitySlotResponse(UUID id, Instant startAt, Instant endAt, String timezone,
		AvailabilityModality modality, String status, AvailabilityReadiness readiness, Instant withdrawnAt,
		Instant createdAt, Instant updatedAt, long version) {

	static AvailabilitySlotResponse from(AvailabilityService.SlotView slot) {
		return new AvailabilitySlotResponse(slot.id(), slot.startAt(), slot.endAt(), slot.timezone(),
				slot.modality(), slot.status(), slot.readiness(), slot.withdrawnAt(), slot.createdAt(),
				slot.updatedAt(), slot.version());
	}

	public record ListResponse(List<AvailabilitySlotResponse> items, int count, Instant generatedAt,
			boolean videoPublishingEnabled) {

		static ListResponse from(AvailabilityService.SlotList slots) {
			var items = slots.items().stream().map(AvailabilitySlotResponse::from).toList();
			return new ListResponse(items, items.size(), slots.generatedAt(), slots.videoPublishingEnabled());
		}
	}
}

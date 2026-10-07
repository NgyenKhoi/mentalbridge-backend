package com.mentalbridge.consultation.analytics;

import java.math.BigDecimal;
import java.time.Instant;

public record SpecialistAnalyticsResponse(String source, Instant generatedAt,
		OperationalStatus operationalStatus, Period period, AvailabilityMetrics availability,
		AppointmentMetrics appointments, RatingMetrics rating, FinancialMetrics financials) {

	public enum DataState {
		AVAILABLE,
		EMPTY,
		BLOCKED,
		UNAVAILABLE,
		STALE
	}

	public enum OperationalStatus {
		READY,
		PROFILE_REQUIRED,
		PENDING_APPROVAL,
		PROFILE_REJECTED,
		SUSPENDED
	}

	public record Period(Instant from, Instant to, String timezone) {
	}

	public record AvailabilityMetrics(String source, Instant asOf, DataState state,
			Long publishedSlotCount, Long utilizedSlotCount, Long unusedSlotCount,
			BigDecimal utilizationRate) {
	}

	public record AppointmentMetrics(String source, Instant asOf, DataState state,
			Long requestedCount, Long acceptedCount, Long rejectedCount, Long expiredCount,
			Long cancelledCount, Long rescheduledCount, Long completedCount, Long userNoShowCount,
			Long specialistNoShowCount, Long bothNoShowCount) {
	}

	public record RatingMetrics(String source, Instant asOf, DataState state,
			BigDecimal averageRating, Long ratingCount) {
	}

	public record FinancialMetrics(String source, Instant asOf, DataState state,
			String currency, Long earnedAmountMinor, Long paidAmountMinor) {
	}
}

package com.mentalbridge.consultation.analytics;

import static com.mentalbridge.consultation.analytics.SpecialistAnalyticsResponse.DataState.AVAILABLE;
import static com.mentalbridge.consultation.analytics.SpecialistAnalyticsResponse.DataState.BLOCKED;
import static com.mentalbridge.consultation.analytics.SpecialistAnalyticsResponse.DataState.EMPTY;
import static com.mentalbridge.consultation.analytics.SpecialistAnalyticsResponse.DataState.UNAVAILABLE;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.analytics.SpecialistAnalyticsResponse.AppointmentMetrics;
import com.mentalbridge.consultation.analytics.SpecialistAnalyticsResponse.AvailabilityMetrics;
import com.mentalbridge.consultation.analytics.SpecialistAnalyticsResponse.DataState;
import com.mentalbridge.consultation.analytics.SpecialistAnalyticsResponse.FinancialMetrics;
import com.mentalbridge.consultation.analytics.SpecialistAnalyticsResponse.OperationalStatus;
import com.mentalbridge.consultation.analytics.SpecialistAnalyticsResponse.Period;
import com.mentalbridge.consultation.analytics.SpecialistAnalyticsResponse.RatingMetrics;
import com.mentalbridge.consultation.shared.ApiException;

@Service
public class SpecialistAnalyticsService {

	private static final String SOURCE = "CONSULTATION";
	private static final Duration DEFAULT_PERIOD = Duration.ofDays(30);
	private static final Duration MAXIMUM_PERIOD = Duration.ofDays(366);

	private final JdbcClient jdbc;
	private final Clock clock;

	public SpecialistAnalyticsService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public SpecialistAnalyticsResponse analytics(UUID specialistId, Instant requestedFrom, Instant requestedTo) {
		var now = clock.instant();
		var period = period(requestedFrom, requestedTo, now);
		var profile = profile(specialistId);
		var operationalStatus = operationalStatus(profile == null ? null : profile.approvalStatus());
		if (profile == null || !profile.readable()) {
			return blocked(now, operationalStatus, period, profile == null ? "UTC" : profile.timezone());
		}

		var explicitPeriod = new Period(period.from(), period.to(), profile.timezone());
		return new SpecialistAnalyticsResponse(SOURCE, now, operationalStatus, explicitPeriod,
				availability(specialistId, period, now), appointments(specialistId, period, now),
				rating(specialistId, now), unavailableFinancials(now));
	}

	private QueryPeriod period(Instant requestedFrom, Instant requestedTo, Instant now) {
		if ((requestedFrom == null) != (requestedTo == null)) {
			throw invalidPeriod("Both from and to are required when filtering analytics");
		}
		var from = requestedFrom == null ? now.minus(DEFAULT_PERIOD) : requestedFrom;
		var to = requestedTo == null ? now : requestedTo;
		if (!from.isBefore(to)) throw invalidPeriod("Analytics from must be before to");
		if (Duration.between(from, to).compareTo(MAXIMUM_PERIOD) > 0) {
			throw invalidPeriod("Analytics period cannot exceed 366 days");
		}
		if (to.isAfter(now)) throw invalidPeriod("Analytics to cannot be after the current server time");
		return new QueryPeriod(from, to);
	}

	private ProfileRow profile(UUID specialistId) {
		return jdbc.sql("""
				select timezone, approval_status from specialist_profile where account_id=:specialistId
				""").param("specialistId", specialistId)
				.query((row, ignored) -> new ProfileRow(row.getString("timezone"), row.getString("approval_status")))
				.optional().orElse(null);
	}

	private AvailabilityMetrics availability(UUID specialistId, QueryPeriod period, Instant now) {
		var row = jdbc.sql("""
				select count(*) as published_count,
				       count(*) filter (where exists (
				         select 1 from appointment a
				         join appointment_status_history h on h.appointment_id=a.id
				         where a.availability_slot_id=s.id and h.to_status='CONFIRMED'
				       )) as utilized_count
				from availability_slot s
				where s.specialist_account_id=:specialistId
				  and s.start_at >= :from and s.start_at < :to
				""").param("specialistId", specialistId).param("from", database(period.from()))
				.param("to", database(period.to()))
				.query((result, ignored) -> new AvailabilityRow(result.getLong("published_count"),
						result.getLong("utilized_count"))).single();
		var unused = row.published() - row.utilized();
		var rate = row.published() == 0 ? null : BigDecimal.valueOf(row.utilized())
				.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(row.published()), 2, RoundingMode.HALF_UP);
		return new AvailabilityMetrics(SOURCE, now, state(row.published()), row.published(), row.utilized(), unused, rate);
	}

	private AppointmentMetrics appointments(UUID specialistId, QueryPeriod period, Instant now) {
		var lifecycle = jdbc.sql("""
				select count(*) filter (where h.to_status='REQUESTED') as requested_count,
				       count(*) filter (where h.to_status='CONFIRMED') as accepted_count,
				       count(*) filter (where h.to_status='REJECTED') as rejected_count,
				       count(*) filter (where h.to_status='EXPIRED') as expired_count,
				       count(*) filter (where h.to_status='CANCELLED' and h.reason <> 'USER_RESCHEDULED') as cancelled_count,
				       count(*) filter (where h.to_status='CANCELLED' and h.reason = 'USER_RESCHEDULED') as rescheduled_count,
				       count(*) filter (where h.to_status='COMPLETED') as completed_count
				from appointment_status_history h
				join appointment a on a.id=h.appointment_id
				where a.specialist_account_id=:specialistId
				  and h.changed_at >= :from and h.changed_at < :to
				""").param("specialistId", specialistId).param("from", database(period.from()))
				.param("to", database(period.to())).query(this::lifecycle).single();
		var noShows = jdbc.sql("""
				select count(*) filter (where session_outcome='USER_NO_SHOW') as user_no_show_count,
				       count(*) filter (where session_outcome='SPECIALIST_NO_SHOW') as specialist_no_show_count,
				       count(*) filter (where session_outcome='BOTH_NO_SHOW') as both_no_show_count
				from appointment
				where specialist_account_id=:specialistId
				  and session_settled_at >= :from and session_settled_at < :to
				""").param("specialistId", specialistId).param("from", database(period.from()))
				.param("to", database(period.to())).query(this::noShows).single();
		var total = lifecycle.total() + noShows.total();
		return new AppointmentMetrics(SOURCE, now, state(total), lifecycle.requested(), lifecycle.accepted(),
				lifecycle.rejected(), lifecycle.expired(), lifecycle.cancelled(), lifecycle.rescheduled(),
				lifecycle.completed(), noShows.user(), noShows.specialist(), noShows.both());
	}

	private RatingMetrics rating(UUID specialistId, Instant now) {
		var row = jdbc.sql("""
				select rating_count, rating_sum from specialist_rating_aggregate
				where specialist_account_id=:specialistId
				""").param("specialistId", specialistId)
				.query((result, ignored) -> new RatingRow(result.getLong("rating_count"), result.getLong("rating_sum")))
				.optional().orElse(null);
		if (row == null) return new RatingMetrics(SOURCE, now, EMPTY, null, 0L);
		var average = BigDecimal.valueOf(row.sum()).divide(BigDecimal.valueOf(row.count()), 2, RoundingMode.HALF_UP);
		return new RatingMetrics(SOURCE, now, AVAILABLE, average, row.count());
	}

	private FinancialMetrics unavailableFinancials(Instant now) {
		return new FinancialMetrics(SOURCE, now, UNAVAILABLE, null, null, null);
	}

	private SpecialistAnalyticsResponse blocked(Instant now, OperationalStatus status,
			QueryPeriod period, String timezone) {
		var blockedAvailability = new AvailabilityMetrics(SOURCE, now, BLOCKED, null, null, null, null);
		var blockedAppointments = new AppointmentMetrics(SOURCE, now, BLOCKED, null, null, null, null, null,
				null, null, null, null, null);
		var blockedRating = new RatingMetrics(SOURCE, now, BLOCKED, null, null);
		var blockedFinancials = new FinancialMetrics(SOURCE, now, BLOCKED, null, null, null);
		return new SpecialistAnalyticsResponse(SOURCE, now, status, new Period(period.from(), period.to(), timezone),
				blockedAvailability, blockedAppointments, blockedRating, blockedFinancials);
	}

	private LifecycleRow lifecycle(ResultSet row, int ignored) throws SQLException {
		return new LifecycleRow(row.getLong("requested_count"), row.getLong("accepted_count"),
				row.getLong("rejected_count"), row.getLong("expired_count"), row.getLong("cancelled_count"),
				row.getLong("rescheduled_count"), row.getLong("completed_count"));
	}

	private NoShowRow noShows(ResultSet row, int ignored) throws SQLException {
		return new NoShowRow(row.getLong("user_no_show_count"), row.getLong("specialist_no_show_count"),
				row.getLong("both_no_show_count"));
	}

	private OperationalStatus operationalStatus(String approvalStatus) {
		if (approvalStatus == null) return OperationalStatus.PROFILE_REQUIRED;
		return switch (approvalStatus) {
			case "APPROVED" -> OperationalStatus.READY;
			case "PENDING" -> OperationalStatus.PENDING_APPROVAL;
			case "REJECTED" -> OperationalStatus.PROFILE_REJECTED;
			case "SUSPENDED" -> OperationalStatus.SUSPENDED;
			default -> OperationalStatus.SUSPENDED;
		};
	}

	private DataState state(long count) {
		return count == 0 ? EMPTY : AVAILABLE;
	}

	private OffsetDateTime database(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private ApiException invalidPeriod(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "SPECIALIST_ANALYTICS_PERIOD_INVALID", message);
	}

	private record QueryPeriod(Instant from, Instant to) { }
	private record ProfileRow(String timezone, String approvalStatus) {
		boolean readable() { return approvalStatus.equals("APPROVED") || approvalStatus.equals("SUSPENDED"); }
	}
	private record AvailabilityRow(long published, long utilized) { }
	private record RatingRow(long count, long sum) { }
	private record LifecycleRow(long requested, long accepted, long rejected, long expired, long cancelled,
			long rescheduled, long completed) {
		long total() { return requested + accepted + rejected + expired + cancelled + rescheduled + completed; }
	}
	private record NoShowRow(long user, long specialist, long both) {
		long total() { return user + specialist + both; }
	}
}

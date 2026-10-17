package com.mentalbridge.consultation.appointment;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.shared.ApiException;

@Service
public class AdminAppointmentQueryService {

	private static final String SOURCE = "CONSULTATION";
	private static final String DATA_STATE = "CURRENT";
	private static final int DEFAULT_LIMIT = 20;
	private static final int MAX_LIMIT = 100;
	private static final Duration MAX_RANGE = Duration.ofDays(180);
	private static final Set<String> STATUSES = Set.of("REQUESTED", "CONFIRMED", "IN_PROGRESS",
			"SESSION_ENDED", "COMPLETED", "REJECTED", "EXPIRED", "CANCELLED");

	private final JdbcClient jdbc;
	private final Clock clock;

	public AdminAppointmentQueryService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public AdminAppointmentResponse.Page search(String status, AppointmentModality modality, Instant from,
			Instant to, UUID userAccountId, UUID specialistAccountId, String encodedCursor,
			Integer requestedLimit) {
		validate(status, from, to, requestedLimit);
		var limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
		var cursor = encodedCursor == null ? null : decodeCursor(encodedCursor);
		var sql = new StringBuilder("""
				select a.id, a.availability_slot_id, a.user_account_id, a.specialist_account_id,
				       a.status, a.modality, a.scheduled_start_at, a.scheduled_end_at,
				       a.display_timezone, a.requested_at, a.decision_deadline_at, a.decided_at,
				       a.decision_reason, a.cancelled_at, a.cancellation_reason,
				       a.cancellation_credit_outcome, a.session_ended_at, a.session_settled_at,
				       a.session_outcome, a.session_outcome_reason, c.state as settlement_state,
				       a.updated_at, a.version
				from appointment a
				join service_credit c on c.id=a.service_credit_id
				where a.scheduled_start_at>=:from and a.scheduled_start_at<:to
				""");
		if (status != null) sql.append(" and a.status=:status");
		if (modality != null) sql.append(" and a.modality=:modality");
		if (userAccountId != null) sql.append(" and a.user_account_id=:userAccountId");
		if (specialistAccountId != null) sql.append(" and a.specialist_account_id=:specialistAccountId");
		if (cursor != null) {
			sql.append(" and (a.scheduled_start_at<:cursorStart or (a.scheduled_start_at=:cursorStart and a.id<:cursorId))");
		}
		sql.append(" order by a.scheduled_start_at desc, a.id desc limit :fetchLimit");

		var statement = jdbc.sql(sql.toString()).param("from", Timestamp.from(from))
				.param("to", Timestamp.from(to)).param("fetchLimit", limit + 1);
		if (status != null) statement.param("status", status);
		if (modality != null) statement.param("modality", modality.name());
		if (userAccountId != null) statement.param("userAccountId", userAccountId);
		if (specialistAccountId != null) statement.param("specialistAccountId", specialistAccountId);
		if (cursor != null) statement.param("cursorStart", Timestamp.from(cursor.scheduledStartAt()))
				.param("cursorId", cursor.appointmentId());

		var found = statement.query((row, ignored) -> new AdminAppointmentResponse.Item(
				row.getObject("id", UUID.class), row.getObject("availability_slot_id", UUID.class),
				row.getObject("user_account_id", UUID.class), row.getObject("specialist_account_id", UUID.class),
				row.getString("status"), AppointmentModality.valueOf(row.getString("modality")),
				row.getTimestamp("scheduled_start_at").toInstant(), row.getTimestamp("scheduled_end_at").toInstant(),
				row.getString("display_timezone"), row.getTimestamp("requested_at").toInstant(),
				row.getTimestamp("decision_deadline_at").toInstant(), instant(row.getTimestamp("decided_at")),
				row.getString("decision_reason"), instant(row.getTimestamp("cancelled_at")),
				row.getString("cancellation_reason"), row.getString("cancellation_credit_outcome"),
				instant(row.getTimestamp("session_ended_at")), instant(row.getTimestamp("session_settled_at")),
				row.getString("session_outcome"), row.getString("session_outcome_reason"),
				row.getString("settlement_state"), row.getTimestamp("updated_at").toInstant(), row.getLong("version")))
				.list();
		var hasNext = found.size() > limit;
		var items = found.subList(0, Math.min(limit, found.size()));
		var nextCursor = hasNext ? encodeCursor(items.getLast()) : null;
		return new AdminAppointmentResponse.Page(SOURCE, DATA_STATE, clock.instant(), from, to,
				items, items.size(), nextCursor);
	}

	private void validate(String status, Instant from, Instant to, Integer limit) {
		if (status != null && !STATUSES.contains(status)) invalid("Unsupported appointment status");
		if (from == null || to == null || !to.isAfter(from) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
			invalid("Appointment range must be positive and no longer than 180 days");
		}
		if (limit != null && (limit < 1 || limit > MAX_LIMIT)) invalid("Limit must be between 1 and 100");
	}

	private String encodeCursor(AdminAppointmentResponse.Item item) {
		var raw = item.scheduledStartAt() + "|" + item.appointmentId();
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private Cursor decodeCursor(String encoded) {
		try {
			if (encoded.isBlank() || encoded.length() > 512) throw new IllegalArgumentException();
			var raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
			var parts = raw.split("\\|", -1);
			if (parts.length != 2) throw new IllegalArgumentException();
			return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
		}
		catch (IllegalArgumentException | DateTimeParseException exception) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ADMIN_APPOINTMENT_QUERY",
					"Appointment cursor is invalid");
		}
	}

	private Instant instant(Timestamp value) {
		return value == null ? null : value.toInstant();
	}

	private void invalid(String detail) {
		throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ADMIN_APPOINTMENT_QUERY", detail);
	}

	private record Cursor(Instant scheduledStartAt, UUID appointmentId) {
	}
}

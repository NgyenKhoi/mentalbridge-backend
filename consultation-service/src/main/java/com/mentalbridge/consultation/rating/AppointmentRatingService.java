package com.mentalbridge.consultation.rating;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.shared.ApiException;

@Service
public class AppointmentRatingService {

	private final JdbcClient jdbc;
	private final Clock clock;

	public AppointmentRatingService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public AppointmentRatingResponse current(UUID userId, UUID appointmentId) {
		requireOwnedAppointment(userId, appointmentId, false);
		return find(appointmentId);
	}

	@Transactional
	public AppointmentRatingResponse save(UUID userId, UUID appointmentId, int value, Long expectedVersion) {
		var appointment = requireOwnedAppointment(userId, appointmentId, true);
		if (!appointment.rateable()) {
			throw new ApiException(HttpStatus.CONFLICT, "APPOINTMENT_NOT_RATEABLE",
					"Only an evidence-completed appointment can be rated");
		}
		var existing = findOptional(appointmentId);
		var now = clock.instant();
		if (existing == null) {
			if (expectedVersion != null) throw stale();
			jdbc.sql("""
					insert into appointment_rating (
					 appointment_id, user_account_id, specialist_account_id, rating, created_at, updated_at
					) values (:appointmentId, :userId, :specialistId, :rating, :now, :now)
					""").param("appointmentId", appointmentId).param("userId", userId)
					.param("specialistId", appointment.specialistId()).param("rating", value)
					.param("now", database(now)).update();
			jdbc.sql("""
					insert into specialist_rating_aggregate (
					 specialist_account_id, rating_count, rating_sum, updated_at
					) values (:specialistId, 1, :rating, :now)
					on conflict (specialist_account_id) do update
					set rating_count=specialist_rating_aggregate.rating_count+1,
					    rating_sum=specialist_rating_aggregate.rating_sum+:rating,
					    version=specialist_rating_aggregate.version+1,
					    updated_at=:now
					""").param("specialistId", appointment.specialistId()).param("rating", value)
					.param("now", database(now)).update();
		}
		else {
			if (expectedVersion == null) {
				throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "RATING_VERSION_REQUIRED",
						"If-Match must contain the quoted current rating version");
			}
			if (existing.version() != expectedVersion) throw stale();
			if (existing.rating() == value) return existing;
			var changed = jdbc.sql("""
					update appointment_rating set rating=:rating, updated_at=:now, version=version+1
					where appointment_id=:appointmentId and version=:version
					""").param("rating", value).param("now", database(now))
					.param("appointmentId", appointmentId).param("version", expectedVersion).update();
			if (changed != 1) throw stale();
			jdbc.sql("""
					update specialist_rating_aggregate
					set rating_sum=rating_sum+:delta, version=version+1, updated_at=:now
					where specialist_account_id=:specialistId
					""").param("delta", value - existing.rating()).param("now", database(now))
					.param("specialistId", appointment.specialistId()).update();
		}
		return find(appointmentId);
	}

	private Appointment requireOwnedAppointment(UUID userId, UUID appointmentId, boolean lock) {
		var sql = """
				select specialist_account_id, status, session_outcome, completion_fact_id
				from appointment where id=:appointmentId and user_account_id=:userId
				""" + (lock ? " for update" : "");
		return jdbc.sql(sql).param("appointmentId", appointmentId).param("userId", userId)
				.query((row, ignored) -> new Appointment(row.getObject("specialist_account_id", UUID.class),
						"COMPLETED".equals(row.getString("status"))
								&& "COMPLETED".equals(row.getString("session_outcome"))
								&& row.getObject("completion_fact_id", UUID.class) != null))
				.optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND",
						"The appointment was not found"));
	}

	private AppointmentRatingResponse find(UUID appointmentId) {
		var rating = findOptional(appointmentId);
		if (rating == null) throw new ApiException(HttpStatus.NOT_FOUND, "APPOINTMENT_RATING_NOT_FOUND",
				"The appointment has not been rated");
		return rating;
	}

	private AppointmentRatingResponse findOptional(UUID appointmentId) {
		return jdbc.sql("""
				select r.appointment_id, r.specialist_account_id, r.rating, r.created_at, r.updated_at, r.version,
				       a.rating_count, a.rating_sum
				from appointment_rating r
				join specialist_rating_aggregate a on a.specialist_account_id=r.specialist_account_id
				where r.appointment_id=:appointmentId
				""").param("appointmentId", appointmentId).query((row, ignored) -> {
					var count = row.getLong("rating_count");
					var average = BigDecimal.valueOf(row.getLong("rating_sum"))
							.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
					return new AppointmentRatingResponse(row.getObject("appointment_id", UUID.class),
							row.getObject("specialist_account_id", UUID.class), row.getInt("rating"),
							row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
							row.getLong("version"), new AppointmentRatingResponse.SpecialistAggregate(average, count));
				}).optional().orElse(null);
	}

	private ApiException stale() {
		return new ApiException(HttpStatus.PRECONDITION_FAILED, "RATING_VERSION_MISMATCH",
				"The rating version is stale");
	}

	private OffsetDateTime database(java.time.Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private record Appointment(UUID specialistId, boolean rateable) { }
}

package com.mentalbridge.consultation.appointment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.ApiException;

@RestController
@RequestMapping("/api/v1/admin/product-journey-metrics")
@PreAuthorize("hasRole('ADMIN')")
public class ConsultationProductJourneyController {

	private static final Duration MAXIMUM_WINDOW = Duration.ofDays(366);
	private final JdbcClient jdbc;
	private final Clock clock;

	public ConsultationProductJourneyController(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@GetMapping
	public Response metrics(@RequestParam String from, @RequestParam String to) {
		Instant start = instant(from);
		Instant end = instant(to);
		validate(start, end);
		Counts counts = jdbc.sql("""
				select count(*) as requested,
				       count(*) filter (where exists (
				         select 1 from appointment_status_history history
				         where history.appointment_id = appointment.id and history.to_status = 'CONFIRMED'
				       )) as confirmed,
				       count(*) filter (where exists (
				         select 1 from appointment_status_history history
				         where history.appointment_id = appointment.id and history.to_status = 'COMPLETED'
				       )) as completed
				from appointment
				where requested_at >= :from and requested_at < :to
				""").param("from", database(start)).param("to", database(end))
				.query((result, row) -> new Counts(result.getLong("requested"), result.getLong("confirmed"),
						result.getLong("completed"))).single();
		return new Response("CONSULTATION", "consultation-product-journey-v1", clock.instant(),
				counts.requested(), counts.confirmed(), counts.completed());
	}

	private Instant instant(String value) {
		try {
			return Instant.parse(value);
		}
		catch (DateTimeParseException exception) {
			throw invalid();
		}
	}

	private void validate(Instant from, Instant to) {
		if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAXIMUM_WINDOW) > 0
				|| to.isAfter(clock.instant().plusSeconds(30))) throw invalid();
	}

	private ApiException invalid() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_JOURNEY_WINDOW",
				"from and to must define a completed UTC window of at most 366 days");
	}

	private OffsetDateTime database(Instant value) { return OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }

	private record Counts(long requested, long confirmed, long completed) { }
	public record Response(String source, String sourceVersion, Instant asOf,
			long consultationsRequested, long consultationsConfirmed, long consultationsCompleted) { }
}

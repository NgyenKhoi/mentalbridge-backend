package com.mentalbridge.consultation.earnings;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.ApiException;

@RestController
public class AdminPayoutController {

	private final JdbcClient jdbc;
	private final Clock clock;

	public AdminPayoutController(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@GetMapping("/api/v1/admin/payouts")
	@Transactional(readOnly = true)
	public AdminPayoutResponse payouts(
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) String toExclusive,
			@RequestParam(required = false) UUID specialistAccountId,
			@RequestParam(required = false) UUID payoutId,
			@RequestParam(required = false) String idempotencyKey,
			@RequestParam(required = false) String providerReference,
			@RequestParam(required = false, defaultValue = "0") Integer offset,
			@RequestParam(required = false, defaultValue = "200") Integer limit) {

		int effectiveLimit = (limit == null || limit <= 0 || limit > 200) ? 200 : limit;
		int effectiveOffset = (offset == null || offset < 0) ? 0 : offset;
		var sql = new StringBuilder("""
				select p.id, p.specialist_account_id, d.display_hint, p.amount_minor, p.currency,
				 p.payout_provider, p.status, count(i.earning_id)::integer earning_count,
				 max(a.provider_payout_reference) provider_reference, p.last_failure_code,
				 p.requested_at, p.completed_at
				from specialist_payout p
				join specialist_payout_destination d on d.id=p.destination_id
				join specialist_payout_item i on i.payout_id=p.id
				left join specialist_payout_attempt a on a.payout_id=p.id
				 and a.attempt_number=(select max(latest.attempt_number) from specialist_payout_attempt latest where latest.payout_id=p.id)
				where 1=1
				""");

		if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
			sql.append(" and p.status = :status");
		}
		if (specialistAccountId != null) {
			sql.append(" and p.specialist_account_id = :specialistAccountId");
		}
		if (payoutId != null) {
			sql.append(" and p.id = :payoutId");
		}
		if (idempotencyKey != null && !idempotencyKey.isBlank()) {
			sql.append(" and p.idempotency_key = :idempotencyKey");
		}
		if (providerReference != null && !providerReference.isBlank()) {
			sql.append(" and exists (select 1 from specialist_payout_attempt ref where ref.payout_id=p.id and ref.provider_payout_reference=:providerReference)");
		}
		if (from != null && !from.isBlank()) {
			sql.append(" and p.requested_at >= :from");
		}
		if (to != null && !to.isBlank()) {
			sql.append(" and p.requested_at <= :to");
		}
		if (toExclusive != null && !toExclusive.isBlank()) {
			sql.append(" and p.requested_at < :toExclusive");
		}

		sql.append("""
				 group by p.id, d.display_hint
				 order by p.requested_at desc, p.id desc limit
				""").append(effectiveLimit).append(" offset ").append(effectiveOffset);

		var query = jdbc.sql(sql.toString());
		if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
			query = query.param("status", status.toUpperCase());
		}
		if (specialistAccountId != null) {
			query = query.param("specialistAccountId", specialistAccountId);
		}
		if (payoutId != null) {
			query = query.param("payoutId", payoutId);
		}
		if (idempotencyKey != null && !idempotencyKey.isBlank()) {
			query = query.param("idempotencyKey", idempotencyKey.trim());
		}
		if (providerReference != null && !providerReference.isBlank()) {
			query = query.param("providerReference", providerReference.trim());
		}
		OffsetDateTime fromDt = null;
		if (to != null && !to.isBlank() && toExclusive != null && !toExclusive.isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "Use either 'to' or 'toExclusive'");
		}
		if (from != null && !from.isBlank()) {
			try {
				fromDt = OffsetDateTime.ofInstant(Instant.parse(from), ZoneOffset.UTC);
				query = query.param("from", fromDt);
			} catch (Exception e) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "Invalid 'from' timestamp format");
			}
		}
		if (to != null && !to.isBlank()) {
			try {
				var toDt = OffsetDateTime.ofInstant(Instant.parse(to), ZoneOffset.UTC);
				if (fromDt != null && toDt.isBefore(fromDt)) {
					throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "'to' date cannot be before 'from' date");
				}
				query = query.param("to", toDt);
			} catch (ApiException e) {
				throw e;
			} catch (Exception e) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "Invalid 'to' timestamp format");
			}
		}
		if (toExclusive != null && !toExclusive.isBlank()) {
			try {
				var end = OffsetDateTime.ofInstant(Instant.parse(toExclusive), ZoneOffset.UTC);
				if (fromDt != null && !end.isAfter(fromDt)) {
					throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "'toExclusive' must be after 'from'");
				}
				query = query.param("toExclusive", end);
			} catch (ApiException e) {
				throw e;
			} catch (Exception e) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "Invalid 'toExclusive' timestamp format");
			}
		}

		var items = query.query((row, ignored) -> new AdminPayoutResponse.Item(
				row.getObject("id", UUID.class),
				row.getObject("specialist_account_id", UUID.class),
				row.getString("display_hint"),
				row.getLong("amount_minor"),
				row.getString("currency"),
				row.getString("payout_provider"),
				row.getString("status"),
				row.getInt("earning_count"),
				row.getString("provider_reference"),
				row.getString("last_failure_code"),
				row.getTimestamp("requested_at").toInstant(),
				instant(row, "completed_at"))).list();

		return new AdminPayoutResponse(clock.instant(), items.size(), items);
	}

	@GetMapping("/api/v1/admin/payouts/{payoutId}")
	@Transactional(readOnly = true)
	public AdminPayoutDetailResponse payoutDetail(@PathVariable UUID payoutId) {
		var payout = jdbc.sql("""
				select p.id, p.specialist_account_id, d.display_hint, p.amount_minor, p.currency,
				 p.payout_provider, p.status, count(i.earning_id)::integer earning_count,
				 max(a.provider_payout_reference) provider_reference, p.last_failure_code,
				 p.requested_at, p.completed_at
				from specialist_payout p
				join specialist_payout_destination d on d.id=p.destination_id
				join specialist_payout_item i on i.payout_id=p.id
				left join specialist_payout_attempt a on a.payout_id=p.id
				 and a.attempt_number=(select max(latest.attempt_number) from specialist_payout_attempt latest where latest.payout_id=p.id)
				where p.id = :payoutId
				group by p.id, d.display_hint
				""").param("payoutId", payoutId)
				.query((row, ignored) -> new AdminPayoutResponse.Item(
						row.getObject("id", UUID.class),
						row.getObject("specialist_account_id", UUID.class),
						row.getString("display_hint"),
						row.getLong("amount_minor"),
						row.getString("currency"),
						row.getString("payout_provider"),
						row.getString("status"),
						row.getInt("earning_count"),
						row.getString("provider_reference"),
						row.getString("last_failure_code"),
						row.getTimestamp("requested_at").toInstant(),
						instant(row, "completed_at")))
				.optional()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYOUT_NOT_FOUND", "Payout was not found"));

		var attempts = jdbc.sql("""
				select id, attempt_number, payout_provider, status, provider_payout_reference,
				 failure_code, requested_at, provider_confirmed_at, failed_at
				from specialist_payout_attempt
				where payout_id = :payoutId
				order by attempt_number asc
				""").param("payoutId", payoutId)
				.query((row, ignored) -> new AdminPayoutDetailResponse.AttemptItem(
						row.getObject("id", UUID.class),
						row.getInt("attempt_number"),
						row.getString("payout_provider"),
						row.getString("status"),
						row.getString("provider_payout_reference"),
						row.getString("failure_code"),
						row.getTimestamp("requested_at").toInstant(),
						instant(row, "provider_confirmed_at"),
						instant(row, "failed_at")))
				.list();

		var earnings = jdbc.sql("""
				select e.id, e.appointment_id, i.amount_minor, e.status, e.earned_at, e.paid_at
				from specialist_payout_item i
				join specialist_earning e on e.id=i.earning_id
				where i.payout_id = :payoutId
				order by e.earned_at asc
				""").param("payoutId", payoutId)
				.query((row, ignored) -> new AdminPayoutDetailResponse.EarningItem(
						row.getObject("id", UUID.class),
						row.getObject("appointment_id", UUID.class),
						row.getLong("amount_minor"),
						row.getString("status"),
						row.getTimestamp("earned_at").toInstant(),
						instant(row, "paid_at")))
				.list();

		var events = jdbc.sql("""
				select e.id, e.payout_provider, e.event_type, e.processing_status, e.failure_code,
				 e.received_at, e.processed_at
				from payout_provider_event e
				join specialist_payout_attempt a on a.id=e.payout_attempt_id
				where a.payout_id = :payoutId
				order by e.received_at asc
				""").param("payoutId", payoutId)
				.query((row, ignored) -> new AdminPayoutDetailResponse.EventItem(
						row.getObject("id", UUID.class),
						row.getString("payout_provider"),
						row.getString("event_type"),
						row.getString("processing_status"),
						row.getString("failure_code"),
						row.getTimestamp("received_at").toInstant(),
						instant(row, "processed_at")))
				.list();

		return new AdminPayoutDetailResponse(payout, attempts, earnings, events);
	}

	private Instant instant(ResultSet row, String column) throws SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}
}

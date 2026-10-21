package com.mentalbridge.consultation.billing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminPaymentController {

	private final JdbcClient jdbc;
	private final Clock clock;

	public AdminPaymentController(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@GetMapping("/api/v1/admin/payments")
	@Transactional(readOnly = true)
	public AdminPaymentResponse payments(
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) UUID subscriptionId,
			@RequestParam(required = false) UUID paymentId,
			@RequestParam(required = false, defaultValue = "200") Integer limit) {

		int effectiveLimit = (limit == null || limit <= 0 || limit > 200) ? 200 : limit;
		var sql = new StringBuilder("""
				select pt.id, pt.subscription_id, pt.target_plan_version_id, v.plan_code, v.pricing_policy_version,
				 pt.purpose, pt.payment_provider, pt.status, pt.amount_minor, pt.currency,
				 pt.created_at, pt.paid_at, pt.failed_at
				from payment_transaction pt join subscription_plan_version v on v.id=pt.target_plan_version_id
				where 1=1
				""");

		if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
			sql.append(" and pt.status = :status");
		}
		if (subscriptionId != null) {
			sql.append(" and pt.subscription_id = :subscriptionId");
		}
		if (paymentId != null) {
			sql.append(" and pt.id = :paymentId");
		}
		if (from != null && !from.isBlank()) {
			sql.append(" and pt.created_at >= :from");
		}
		if (to != null && !to.isBlank()) {
			sql.append(" and pt.created_at <= :to");
		}
		sql.append(" order by pt.created_at desc, pt.id desc limit ").append(effectiveLimit);

		var query = jdbc.sql(sql.toString());
		if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
			query = query.param("status", status.toUpperCase());
		}
		if (subscriptionId != null) {
			query = query.param("subscriptionId", subscriptionId);
		}
		if (paymentId != null) {
			query = query.param("paymentId", paymentId);
		}
		if (from != null && !from.isBlank()) {
			query = query.param("from", OffsetDateTime.ofInstant(Instant.parse(from), ZoneOffset.UTC));
		}
		if (to != null && !to.isBlank()) {
			query = query.param("to", OffsetDateTime.ofInstant(Instant.parse(to), ZoneOffset.UTC));
		}

		var items = query.query((row, ignored) -> new AdminPaymentResponse.Item(
				row.getObject("id", UUID.class),
				row.getObject("subscription_id", UUID.class),
				row.getObject("target_plan_version_id", UUID.class),
				row.getString("plan_code"),
				row.getString("pricing_policy_version"),
				row.getString("purpose"),
				row.getString("payment_provider"),
				row.getString("status"),
				row.getLong("amount_minor"),
				row.getString("currency"),
				row.getTimestamp("created_at").toInstant(),
				instant(row, "paid_at"),
				instant(row, "failed_at"))).list();

		return new AdminPaymentResponse(clock.instant(), items.size(), items);
	}

	private Instant instant(ResultSet row, String column) throws SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}
}

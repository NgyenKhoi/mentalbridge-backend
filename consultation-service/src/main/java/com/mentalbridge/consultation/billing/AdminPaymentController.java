package com.mentalbridge.consultation.billing;

import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
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
	AdminPaymentResponse payments() {
		var items = jdbc.sql("""
				select pt.id, pt.subscription_id, pt.target_plan_version_id, v.plan_code, v.pricing_policy_version,
				 pt.purpose, pt.payment_provider, pt.status, pt.amount_minor, pt.currency,
				 pt.created_at, pt.paid_at, pt.failed_at
				from payment_transaction pt join subscription_plan_version v on v.id=pt.target_plan_version_id
				order by pt.created_at desc, pt.id desc limit 200
				""").query((row, ignored) -> new AdminPaymentResponse.Item(row.getObject("id", UUID.class),
				row.getObject("subscription_id", UUID.class), row.getObject("target_plan_version_id", UUID.class),
				row.getString("plan_code"), row.getString("pricing_policy_version"), row.getString("purpose"),
				row.getString("payment_provider"), row.getString("status"), row.getLong("amount_minor"),
				row.getString("currency"), row.getTimestamp("created_at").toInstant(),
				instant(row, "paid_at"), instant(row, "failed_at"))).list();
		return new AdminPaymentResponse(clock.instant(), items.size(), items);
	}

	private java.time.Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}
}

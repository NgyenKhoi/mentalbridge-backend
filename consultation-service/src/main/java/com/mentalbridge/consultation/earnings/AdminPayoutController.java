package com.mentalbridge.consultation.earnings;

import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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
	AdminPayoutResponse payouts() {
		var items = jdbc.sql("""
				select p.id, p.specialist_account_id, d.display_hint, p.amount_minor, p.currency,
				 p.payout_provider, p.status, count(i.earning_id)::integer earning_count,
				 max(a.provider_payout_reference) provider_reference, p.last_failure_code,
				 p.requested_at, p.completed_at
				from specialist_payout p
				join specialist_payout_destination d on d.id=p.destination_id
				join specialist_payout_item i on i.payout_id=p.id
				left join specialist_payout_attempt a on a.payout_id=p.id
				group by p.id, d.display_hint
				order by p.requested_at desc, p.id desc limit 200
				""").query((row, ignored) -> new AdminPayoutResponse.Item(row.getObject("id", UUID.class),
				row.getObject("specialist_account_id", UUID.class), row.getString("display_hint"),
				row.getLong("amount_minor"), row.getString("currency"), row.getString("payout_provider"),
				row.getString("status"), row.getInt("earning_count"), row.getString("provider_reference"),
				row.getString("last_failure_code"), row.getTimestamp("requested_at").toInstant(),
				instant(row, "completed_at"))).list();
		return new AdminPayoutResponse(clock.instant(), items.size(), items);
	}

	private java.time.Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}
}

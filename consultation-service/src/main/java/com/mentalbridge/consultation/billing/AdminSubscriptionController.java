package com.mentalbridge.consultation.billing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.ApiException;

@RestController
public class AdminSubscriptionController {

	private final JdbcClient jdbc;

	public AdminSubscriptionController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping("/api/v1/admin/subscriptions/{subscriptionId}")
	@Transactional(readOnly = true)
	public AdminSubscriptionDetailResponse subscriptionDetail(@PathVariable UUID subscriptionId) {
		var sub = jdbc.sql("""
				select s.id, s.user_id, s.plan_version_id, v.plan_code, v.pricing_policy_version,
				 s.status, s.current_period_start, s.current_period_end, s.created_at
				from user_subscription s
				join subscription_plan_version v on v.id=s.plan_version_id
				where s.id = :subscriptionId
				""").param("subscriptionId", subscriptionId)
				.query((row, ignored) -> new SubHeader(
						row.getObject("id", UUID.class),
						row.getObject("user_id", UUID.class),
						row.getObject("plan_version_id", UUID.class),
						row.getString("plan_code"),
						row.getString("pricing_policy_version"),
						row.getString("status"),
						instant(row, "current_period_start"),
						instant(row, "current_period_end"),
						row.getTimestamp("created_at").toInstant()))
				.optional()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND", "Subscription was not found"));

		var payments = jdbc.sql("""
				select pt.id, pt.subscription_id, pt.target_plan_version_id, v.plan_code, v.pricing_policy_version,
				 pt.purpose, pt.payment_provider, pt.status, pt.amount_minor, pt.currency,
				 pt.created_at, pt.paid_at, pt.failed_at
				from payment_transaction pt
				join subscription_plan_version v on v.id=pt.target_plan_version_id
				where pt.subscription_id = :subscriptionId
				order by pt.created_at desc
				""").param("subscriptionId", subscriptionId)
				.query((row, ignored) -> new AdminPaymentResponse.Item(
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
						instant(row, "failed_at")))
				.list();

		var credits = jdbc.sql("""
				select c.id, c.period_id, c.ordinal, c.state, p.credit_allocation_minor, c.appointment_id, c.created_at
				from service_credit c
				join service_credit_period p on p.id=c.period_id
				where (p.source = 'PAID' and p.source_reference = :subIdText)
				   or exists (
				       select 1 from subscription_upgrade_credit uc
				       join subscription_upgrade u on u.id = uc.upgrade_id
				       where uc.credit_id = c.id and u.subscription_id = :subscriptionId
				   )
				order by p.period_start desc, c.ordinal asc
				""").param("subIdText", subscriptionId.toString()).param("subscriptionId", subscriptionId)
				.query((row, ignored) -> new AdminSubscriptionDetailResponse.CreditItem(
						row.getObject("id", UUID.class),
						row.getObject("period_id", UUID.class),
						row.getInt("ordinal"),
						row.getString("state"),
						row.getLong("credit_allocation_minor"),
						row.getObject("appointment_id", UUID.class),
						row.getTimestamp("created_at").toInstant()))
				.list();

		var upgrades = jdbc.sql("""
				select id, from_plan_version_id, to_plan_version_id, offset_minor, amount_due_minor,
				 status, quoted_at, applied_at
				from subscription_upgrade
				where subscription_id = :subscriptionId
				order by quoted_at desc
				""").param("subscriptionId", subscriptionId)
				.query((row, ignored) -> new AdminSubscriptionDetailResponse.UpgradeItem(
						row.getObject("id", UUID.class),
						row.getObject("from_plan_version_id", UUID.class),
						row.getObject("to_plan_version_id", UUID.class),
						row.getLong("offset_minor"),
						row.getLong("amount_due_minor"),
						row.getString("status"),
						row.getTimestamp("quoted_at").toInstant(),
						instant(row, "applied_at")))
				.list();

		return new AdminSubscriptionDetailResponse(
				sub.id(),
				sub.userId(),
				sub.planVersionId(),
				sub.planCode(),
				sub.pricingPolicyVersion(),
				sub.status(),
				sub.currentPeriodStart(),
				sub.currentPeriodEnd(),
				sub.createdAt(),
				payments,
				credits,
				upgrades);
	}

	private static Instant instant(ResultSet row, String column) throws SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private record SubHeader(
			UUID id,
			UUID userId,
			UUID planVersionId,
			String planCode,
			String pricingPolicyVersion,
			String status,
			Instant currentPeriodStart,
			Instant currentPeriodEnd,
			Instant createdAt) {
	}
}


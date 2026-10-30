package com.mentalbridge.consultation.billing;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mentalbridge.consultation.credits.ServiceCreditService;

@Service
class PaymentSettlementService {

    private static final String PAYMENT_SELECT = """
            select pt.id, pt.subscription_id, pt.target_plan_version_id, pt.momo_request_id,
             pt.amount_minor, pt.status, pt.purpose, pt.expires_at, pt.momo_response_time_epoch_ms,
             pt.payment_provider, pt.order_code, pt.payos_payment_link_id, s.user_id
            from payment_transaction pt join user_subscription s on s.id=pt.subscription_id
            """;
    private final JdbcClient jdbc;
    private final ServiceCreditService credits;

    PaymentSettlementService(JdbcClient jdbc, ServiceCreditService credits) {
        this.jdbc = jdbc;
        this.credits = credits;
    }

    Payment lock(UUID paymentId) {
        return jdbc.sql(PAYMENT_SELECT + " where pt.id=:id for update of pt")
                .param("id", paymentId).query(this::payment).optional().orElse(null);
    }

    Payment lockPayos(long orderCode) {
        return jdbc.sql(PAYMENT_SELECT + " where pt.payment_provider='PAYOS' and pt.order_code=:code for update of pt")
                .param("code", orderCode).query(this::payment).optional().orElse(null);
    }

    Payment lockMomo(String orderId) {
        return jdbc.sql(PAYMENT_SELECT + " where pt.payment_provider in ('MOMO','FAKE') and pt.momo_order_id=:id for update of pt")
                .param("id", orderId).query(this::payment).optional().orElse(null);
    }

    boolean succeed(Payment payment, Instant now) {
        if (payment == null || !"PENDING".equals(payment.status()) || !payment.expiresAt().isAfter(now)) return false;
        var changed = jdbc.sql("""
                update payment_transaction set status='SUCCEEDED', terminal_reason=null, paid_at=:now,
                 updated_at=:now, version=version+1 where id=:id and status='PENDING' and expires_at>:now
                """).param("now", databaseInstant(now)).param("id", payment.id()).update();
        if (changed == 0) return false;
		if ("UPGRADE".equals(payment.purpose())) {
			jdbc.sql("""
					update service_credit c set state='REVOKED', updated_at=:now, version=c.version+1
					from subscription_upgrade_credit uc join subscription_upgrade u on u.id=uc.upgrade_id
					where u.payment_id=:paymentId and uc.credit_id=c.id and c.state='UPGRADE_HELD'
					""").param("now", databaseInstant(now)).param("paymentId", payment.id()).update();
			jdbc.sql("""
					update subscription_upgrade set status='APPLIED', applied_at=:now, updated_at=:now, version=version+1
					where payment_id=:paymentId and status='PENDING_PAYMENT'
					""").param("now", databaseInstant(now)).param("paymentId", payment.id()).update();
		}
		var plan = plan(payment.targetPlanVersionId());
		var periodEnd = OffsetDateTime.ofInstant(now, ZoneOffset.UTC).plusMonths(plan.billingMonths()).toInstant();
		jdbc.sql("""
				update user_subscription set plan_version_id=:planVersionId, status='ACTIVE',
				 current_period_start=:now, current_period_end=:periodEnd, ended_at=null,
				 updated_at=:now, version=version+1 where id=:id
				""").param("planVersionId", plan.id()).param("now", databaseInstant(now))
				.param("periodEnd", databaseInstant(periodEnd)).param("id", payment.subscriptionId()).update();
		jdbc.sql("""
				insert into subscription_status_history (id, subscription_id, from_status, to_status, reason_code, changed_at)
				values (:id, :subscriptionId, :fromStatus, 'ACTIVE', :reason, :now)
				""").param("id", UUID.randomUUID()).param("subscriptionId", payment.subscriptionId())
				.param("fromStatus", "PURCHASE".equals(payment.purpose()) ? "PENDING_PAYMENT" : "ACTIVE")
				.param("reason", "PURCHASE".equals(payment.purpose()) ? "PURCHASE_PAYMENT_CONFIRMED" : "UPGRADE_PAYMENT_CONFIRMED")
				.param("now", databaseInstant(now)).update();
		jdbc.sql("""
				insert into current_service_entitlement (account_id, package_code, source, source_reference,
				 established_by, effective_from, effective_until, policy_version, created_at, updated_at, version)
				values (:userId, :planCode, 'PAID', :source, null, :now, :periodEnd, 'service-entitlement-v1', :now, :now, 0)
				on conflict (account_id) do update set package_code=excluded.package_code, source='PAID',
				 source_reference=excluded.source_reference, established_by=null, effective_from=excluded.effective_from,
				 effective_until=excluded.effective_until, policy_version=excluded.policy_version,
				 updated_at=excluded.updated_at, version=current_service_entitlement.version+1
				""").param("userId", payment.userId()).param("planCode", plan.planCode())
				.param("source", payment.subscriptionId().toString()).param("now", databaseInstant(now))
				.param("periodEnd", databaseInstant(periodEnd)).update();
		credits.current(payment.userId());
        return true;
    }

    boolean fail(Payment payment, Instant now, String terminalReason) {
        if (payment == null || !"PENDING".equals(payment.status())) return false;
        var changed = jdbc.sql("""
                update payment_transaction set status='FAILED', terminal_reason=:reason, failed_at=:now,
                 updated_at=:now, version=version+1 where id=:id and status='PENDING'
                """).param("reason", terminalReason).param("now", databaseInstant(now)).param("id", payment.id()).update();
        if (changed == 0) return false;
        var reason = switch (terminalReason) {
            case "EXPIRED" -> "PAYMENT_EXPIRED";
            case "CANCELLED" -> "PAYMENT_CANCELLED";
            default -> "PROVIDER_PAYMENT_FAILED";
        };
		if ("UPGRADE".equals(payment.purpose())) {
			jdbc.sql("""
					update service_credit c set state=case when p.period_end > :now then 'AVAILABLE' else 'EXPIRED' end,
					 updated_at=:now, version=c.version+1
					from service_credit_period p, subscription_upgrade_credit uc, subscription_upgrade u
					where c.period_id=p.id and u.id=uc.upgrade_id and uc.credit_id=c.id
					 and u.payment_id=:paymentId and c.state='UPGRADE_HELD'
					""").param("now", databaseInstant(now)).param("paymentId", payment.id()).update();
			jdbc.sql("""
					update subscription_upgrade set status=:status, updated_at=:now, version=version+1
					where payment_id=:paymentId and status='PENDING_PAYMENT'
					""").param("status", "EXPIRED".equals(terminalReason) ? "EXPIRED" : "FAILED")
					.param("now", databaseInstant(now)).param("paymentId", payment.id()).update();
		}
		else {
			jdbc.sql("""
					update user_subscription set status='ENDED', ended_at=:now, updated_at=:now, version=version+1
					where id=:id and status='PENDING_PAYMENT'
					""").param("now", databaseInstant(now)).param("id", payment.subscriptionId()).update();
			jdbc.sql("""
					insert into subscription_status_history (id, subscription_id, from_status, to_status, reason_code, changed_at)
					values (:id, :subscriptionId, 'PENDING_PAYMENT', 'ENDED', :reason, :now)
					""").param("id", UUID.randomUUID()).param("subscriptionId", payment.subscriptionId())
					.param("reason", reason).param("now", databaseInstant(now)).update();
		}
        return true;
    }

    private Plan plan(UUID id) {
        return jdbc.sql("""
                select id, plan_code, billing_period_months from subscription_plan_version where id=:id and currency='VND'
                """).param("id", id).query((row, ignored) -> new Plan(row.getObject("id", UUID.class),
                row.getString("plan_code"), row.getInt("billing_period_months"))).single();
    }

    private Payment payment(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        return new Payment(row.getObject("id", UUID.class), row.getObject("subscription_id", UUID.class),
                row.getObject("target_plan_version_id", UUID.class), row.getString("momo_request_id"),
                row.getLong("amount_minor"), row.getString("status"), row.getString("purpose"),
                row.getTimestamp("expires_at").toInstant(), nullableLong(row, "momo_response_time_epoch_ms"),
                row.getObject("user_id", UUID.class), row.getString("payment_provider"),
                nullableLong(row, "order_code"), row.getString("payos_payment_link_id"));
    }

    private Long nullableLong(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        var value = row.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private OffsetDateTime databaseInstant(Instant value) { return OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }
    record Payment(UUID id, UUID subscriptionId, UUID targetPlanVersionId, String requestId, long amount,
            String status, String purpose, Instant expiresAt, Long responseTime, UUID userId,
            String provider, Long orderCode, String paymentLinkId) { }
    private record Plan(UUID id, String planCode, int billingMonths) { }
}

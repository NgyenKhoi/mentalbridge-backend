package com.mentalbridge.consultation.reconciliation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.billing.AdminPaymentResponse;
import com.mentalbridge.consultation.earnings.AdminPayoutResponse;
import com.mentalbridge.consultation.shared.ApiException;

@RestController
public class AdminCommercialReconciliationController {

	private final JdbcClient jdbc;
	private final Clock clock;

	public AdminCommercialReconciliationController(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@GetMapping("/api/v1/admin/commercial/discrepancies")
	@Transactional(readOnly = true)
	public AdminCommercialDiscrepancyResponse discrepancies() {
		var webhooks = jdbc.sql("""
				select id, order_id, 'MOMO' as provider, trans_id, processing_status, failure_code, received_at,
				 case when processing_status='RECEIVED' then 'PENDING_PROCESSING'
				      when processing_status='VERIFIED_UNMATCHED' then 'VERIFIED_UNMATCHED'
				      when processing_status='PROCESSING_FAILED' then 'PROCESSING_FAILED'
				      else 'DISCREPANCY' end as discrepancy_type
				from momo_payment_ipn
				where processing_status <> 'PROCESSED'
				union all
				select p1.id, p1.order_id, 'MOMO' as provider, p1.trans_id, p1.processing_status, p1.failure_code, p1.received_at,
				 'DUPLICATE_DELIVERY' as discrepancy_type
				from momo_payment_ipn p1
				where exists (select 1 from momo_payment_ipn p2 where p2.order_id = p1.order_id and p2.id <> p1.id)
				order by received_at desc limit 50
				""").query((row, ignored) -> new AdminCommercialDiscrepancyResponse.WebhookDiscrepancy(
						row.getObject("id", UUID.class),
						row.getString("order_id"),
						row.getString("provider"),
						row.getLong("trans_id"),
						row.getString("discrepancy_type"),
						row.getString("processing_status"),
						row.getString("failure_code"),
						row.getTimestamp("received_at").toInstant()))
				.list();

		var payouts = jdbc.sql("""
				select p.id, p.specialist_account_id, d.display_hint, p.amount_minor, p.currency, p.payout_provider,
				 p.status, p.last_failure_code, p.requested_at
				from specialist_payout p
				join specialist_payout_destination d on d.id=p.destination_id
				where p.status in ('FAILED', 'UNKNOWN') or p.last_failure_code is not null
				order by p.requested_at desc limit 50
				""").query((row, ignored) -> new AdminCommercialDiscrepancyResponse.PayoutDiscrepancy(
						row.getObject("id", UUID.class),
						row.getObject("specialist_account_id", UUID.class),
						row.getString("display_hint"),
						row.getLong("amount_minor"),
						row.getString("currency"),
						row.getString("payout_provider"),
						row.getString("status"),
						row.getString("last_failure_code"),
						row.getTimestamp("requested_at").toInstant()))
				.list();

		var heldEarnings = jdbc.sql("""
				select e.id, e.appointment_id, e.specialist_account_id, e.specialist_amount_minor, e.status,
				 e.earned_at, e.settlement_available_at,
				 case when exists (select 1 from appointment_dispute d where d.appointment_id=e.appointment_id and d.status='OPEN') then 'APPOINTMENT_DISPUTED'
				      when e.settlement_available_at > current_timestamp then 'SETTLEMENT_WINDOW_ACTIVE'
				      else 'HELD_PENDING_PROMOTION' end as hold_reason
				from specialist_earning e
				where e.status = 'PENDING_SETTLEMENT'
				   or exists (select 1 from appointment_dispute d where d.appointment_id=e.appointment_id and d.status='OPEN')
				order by e.earned_at desc limit 50
				""").query((row, ignored) -> new AdminCommercialDiscrepancyResponse.HeldEarningDiscrepancy(
						row.getObject("id", UUID.class),
						row.getObject("appointment_id", UUID.class),
						row.getObject("specialist_account_id", UUID.class),
						row.getLong("specialist_amount_minor"),
						row.getString("status"),
						row.getString("hold_reason"),
						row.getTimestamp("earned_at").toInstant(),
						row.getTimestamp("settlement_available_at").toInstant()))
				.list();

		var disputes = jdbc.sql("""
				select id, appointment_id, opened_by_role, reason_code, status, resolution_outcome, opened_at, eligible_until
				from appointment_dispute
				where status = 'OPEN' or resolution_outcome = 'RELEASE_USER_CREDIT'
				order by opened_at desc limit 50
				""").query((row, ignored) -> new AdminCommercialDiscrepancyResponse.DisputeDiscrepancy(
						row.getObject("id", UUID.class),
						row.getObject("appointment_id", UUID.class),
						row.getString("opened_by_role"),
						row.getString("reason_code"),
						row.getString("status"),
						row.getString("resolution_outcome"),
						row.getTimestamp("opened_at").toInstant(),
						row.getTimestamp("eligible_until").toInstant()))
				.list();

		int total = webhooks.size() + payouts.size() + heldEarnings.size() + disputes.size();
		return new AdminCommercialDiscrepancyResponse(clock.instant(), total, webhooks, payouts, heldEarnings, disputes);
	}

	@GetMapping("/api/v1/admin/commercial/provenance")
	@Transactional(readOnly = true)
	public AdminCommercialProvenanceResponse provenance(
			@RequestParam(required = false) UUID paymentId,
			@RequestParam(required = false) UUID subscriptionId,
			@RequestParam(required = false) UUID creditId,
			@RequestParam(required = false) UUID appointmentId,
			@RequestParam(required = false) UUID earningId,
			@RequestParam(required = false) UUID payoutId) {

		String queryType;
		String queryValue;
		if (paymentId != null) {
			queryType = "PAYMENT";
			queryValue = paymentId.toString();
		} else if (subscriptionId != null) {
			queryType = "SUBSCRIPTION";
			queryValue = subscriptionId.toString();
		} else if (creditId != null) {
			queryType = "CREDIT";
			queryValue = creditId.toString();
		} else if (appointmentId != null) {
			queryType = "APPOINTMENT";
			queryValue = appointmentId.toString();
		} else if (earningId != null) {
			queryType = "EARNING";
			queryValue = earningId.toString();
		} else if (payoutId != null) {
			queryType = "PAYOUT";
			queryValue = payoutId.toString();
		} else {
			throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_PROVENANCE_IDENTIFIER",
					"At least one identifier (paymentId, subscriptionId, creditId, appointmentId, earningId, payoutId) must be provided");
		}

		UUID resolvedSubscriptionId = subscriptionId;
		Set<UUID> appointmentIds = new HashSet<>();
		if (appointmentId != null) appointmentIds.add(appointmentId);
		Set<UUID> creditIds = new HashSet<>();
		if (creditId != null) creditIds.add(creditId);
		Set<UUID> earningIds = new HashSet<>();
		if (earningId != null) earningIds.add(earningId);
		Set<UUID> payoutIds = new HashSet<>();
		if (payoutId != null) payoutIds.add(payoutId);

		// Resolve from paymentId
		if (paymentId != null && resolvedSubscriptionId == null) {
			resolvedSubscriptionId = jdbc.sql("select subscription_id from payment_transaction where id=:id")
					.param("id", paymentId).query(UUID.class).optional().orElse(null);
		}

		// Resolve from payoutId
		if (payoutId != null) {
			var relatedEarnings = jdbc.sql("select earning_id from specialist_payout_item where payout_id=:payoutId")
					.param("payoutId", payoutId).query(UUID.class).list();
			earningIds.addAll(relatedEarnings);
		}

		// Resolve from earningIds
		for (var eId : new ArrayList<>(earningIds)) {
			var appt = jdbc.sql("select appointment_id, consumed_credit_id from specialist_earning where id=:id")
					.param("id", eId).query((row, ignored) -> new Pair(row.getObject("appointment_id", UUID.class),
							row.getObject("consumed_credit_id", UUID.class)))
					.optional().orElse(null);
			if (appt != null) {
				if (appt.first != null) appointmentIds.add(appt.first);
				if (appt.second != null) creditIds.add(appt.second);
			}
			var pId = jdbc.sql("select payout_id from specialist_payout_item where earning_id=:id")
					.param("id", eId).query(UUID.class).optional().orElse(null);
			if (pId != null) payoutIds.add(pId);
		}

		// Resolve from appointmentIds
		for (var aId : new ArrayList<>(appointmentIds)) {
			var credId = jdbc.sql("select service_credit_id from appointment where id=:id")
					.param("id", aId).query(UUID.class).optional().orElse(null);
			if (credId != null) creditIds.add(credId);

			var eId = jdbc.sql("select id from specialist_earning where appointment_id=:id")
					.param("id", aId).query(UUID.class).optional().orElse(null);
			if (eId != null) {
				earningIds.add(eId);
				var pId = jdbc.sql("select payout_id from specialist_payout_item where earning_id=:id")
						.param("id", eId).query(UUID.class).optional().orElse(null);
				if (pId != null) payoutIds.add(pId);
			}
		}

		// Resolve from creditIds
		for (var cId : new ArrayList<>(creditIds)) {
			var periodRef = jdbc.sql("""
					select p.source_reference, c.appointment_id
					from service_credit c join service_credit_period p on p.id=c.period_id
					where c.id=:id
					""").param("id", cId).query((row, ignored) -> new PairRef(
							row.getString("source_reference"),
							row.getObject("appointment_id", UUID.class)))
					.optional().orElse(null);
			if (periodRef != null) {
				if (periodRef.appointmentId != null) appointmentIds.add(periodRef.appointmentId);
				if (resolvedSubscriptionId == null && periodRef.sourceRef != null) {
					try {
						resolvedSubscriptionId = UUID.fromString(periodRef.sourceRef);
					} catch (IllegalArgumentException ignored) {
					}
				}
			}
		}

		// If resolvedSubscriptionId found, load subscription & all credits/appointments from that subscription
		AdminCommercialProvenanceResponse.AdminSubscriptionSummary subscriptionSummary = null;
		List<AdminPaymentResponse.Item> payments = Collections.emptyList();
		List<AdminCommercialProvenanceResponse.AdminCreditSummary> credits = Collections.emptyList();

		if (resolvedSubscriptionId != null) {
			subscriptionSummary = jdbc.sql("""
					select s.id, s.user_id, s.plan_version_id, v.plan_code, v.pricing_policy_version,
					 s.status, s.current_period_start, s.current_period_end, s.created_at
					from user_subscription s
					join subscription_plan_version v on v.id=s.plan_version_id
					where s.id=:id
					""").param("id", resolvedSubscriptionId)
					.query((row, ignored) -> new AdminCommercialProvenanceResponse.AdminSubscriptionSummary(
							row.getObject("id", UUID.class),
							row.getObject("user_id", UUID.class),
							row.getObject("plan_version_id", UUID.class),
							row.getString("plan_code"),
							row.getString("pricing_policy_version"),
							row.getString("status"),
							instant(row, "current_period_start"),
							instant(row, "current_period_end"),
							row.getTimestamp("created_at").toInstant()))
					.optional().orElse(null);

			payments = jdbc.sql("""
					select pt.id, pt.subscription_id, pt.target_plan_version_id, v.plan_code, v.pricing_policy_version,
					 pt.purpose, pt.payment_provider, pt.status, pt.amount_minor, pt.currency,
					 pt.created_at, pt.paid_at, pt.failed_at
					from payment_transaction pt join subscription_plan_version v on v.id=pt.target_plan_version_id
					where pt.subscription_id=:subId
					order by pt.created_at desc
					""").param("subId", resolvedSubscriptionId)
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

			credits = jdbc.sql("""
					select c.id, c.period_id, c.ordinal, c.state, p.credit_allocation_minor, c.appointment_id, c.created_at
					from service_credit c
					join service_credit_period p on p.id=c.period_id
					where p.source_reference=:subIdText or p.account_id=(select user_id from user_subscription where id=:subId)
					order by p.period_start desc, c.ordinal asc
					""").param("subIdText", resolvedSubscriptionId.toString()).param("subId", resolvedSubscriptionId)
					.query((row, ignored) -> new AdminCommercialProvenanceResponse.AdminCreditSummary(
							row.getObject("id", UUID.class),
							row.getObject("period_id", UUID.class),
							row.getInt("ordinal"),
							row.getString("state"),
							row.getLong("credit_allocation_minor"),
							row.getObject("appointment_id", UUID.class),
							row.getTimestamp("created_at").toInstant()))
					.list();

			for (var cr : credits) {
				if (cr.appointmentId() != null) appointmentIds.add(cr.appointmentId());
			}
		}

		// Re-expand earnings and payouts from all discovered appointmentIds
		for (var aId : new ArrayList<>(appointmentIds)) {
			var eId = jdbc.sql("select id from specialist_earning where appointment_id=:id")
					.param("id", aId).query(UUID.class).optional().orElse(null);
			if (eId != null) {
				earningIds.add(eId);
				var pId = jdbc.sql("select payout_id from specialist_payout_item where earning_id=:id")
						.param("id", eId).query(UUID.class).optional().orElse(null);
				if (pId != null) payoutIds.add(pId);
			}
		}

		// Load appointments
		List<AdminCommercialProvenanceResponse.AdminAppointmentSummary> appointments = Collections.emptyList();
		if (!appointmentIds.isEmpty()) {
			var inClause = String.join(",", appointmentIds.stream().map(id -> "'" + id + "'").toList());
			appointments = jdbc.sql("""
					select id, specialist_account_id, service_credit_id, status, modality, session_outcome,
					 completion_fact_id, scheduled_start_at, scheduled_end_at, session_settled_at
					from appointment where id in (""" + inClause + ") order by scheduled_start_at desc")
					.query((row, ignored) -> new AdminCommercialProvenanceResponse.AdminAppointmentSummary(
							row.getObject("id", UUID.class),
							row.getObject("specialist_account_id", UUID.class),
							row.getObject("service_credit_id", UUID.class),
							row.getString("status"),
							row.getString("modality"),
							row.getString("session_outcome"),
							row.getObject("completion_fact_id", UUID.class),
							row.getTimestamp("scheduled_start_at").toInstant(),
							row.getTimestamp("scheduled_end_at").toInstant(),
							instant(row, "session_settled_at")))
					.list();
		}

		// Load disputes
		List<AdminCommercialProvenanceResponse.AdminDisputeSummary> disputes = Collections.emptyList();
		if (!appointmentIds.isEmpty()) {
			var inClause = String.join(",", appointmentIds.stream().map(id -> "'" + id + "'").toList());
			disputes = jdbc.sql("""
					select id, appointment_id, opened_by_role, reason_code, status, resolution_outcome,
					 resolution_reason, opened_at, resolved_at
					from appointment_dispute where appointment_id in (""" + inClause + ") order by opened_at desc")
					.query((row, ignored) -> new AdminCommercialProvenanceResponse.AdminDisputeSummary(
							row.getObject("id", UUID.class),
							row.getObject("appointment_id", UUID.class),
							row.getString("opened_by_role"),
							row.getString("reason_code"),
							row.getString("status"),
							row.getString("resolution_outcome"),
							row.getString("resolution_reason"),
							row.getTimestamp("opened_at").toInstant(),
							instant(row, "resolved_at")))
					.list();
		}

		// Load earnings
		List<AdminCommercialProvenanceResponse.AdminEarningSummary> earnings = Collections.emptyList();
		if (!earningIds.isEmpty()) {
			var inClause = String.join(",", earningIds.stream().map(id -> "'" + id + "'").toList());
			earnings = jdbc.sql("""
					select id, appointment_id, consumed_credit_id, specialist_account_id, plan_version,
					 credit_allocation_minor, specialist_share_bps, specialist_amount_minor,
					 platform_allocation_minor, status, earned_at, settlement_available_at, paid_at
					from specialist_earning where id in (""" + inClause + ") order by earned_at desc")
					.query((row, ignored) -> new AdminCommercialProvenanceResponse.AdminEarningSummary(
							row.getObject("id", UUID.class),
							row.getObject("appointment_id", UUID.class),
							row.getObject("consumed_credit_id", UUID.class),
							row.getObject("specialist_account_id", UUID.class),
							row.getString("plan_version"),
							row.getLong("credit_allocation_minor"),
							row.getInt("specialist_share_bps"),
							row.getLong("specialist_amount_minor"),
							row.getLong("platform_allocation_minor"),
							row.getString("status"),
							row.getTimestamp("earned_at").toInstant(),
							row.getTimestamp("settlement_available_at").toInstant(),
							instant(row, "paid_at")))
					.list();
		}

		// Load payouts
		List<AdminPayoutResponse.Item> payouts = Collections.emptyList();
		if (!payoutIds.isEmpty()) {
			var inClause = String.join(",", payoutIds.stream().map(id -> "'" + id + "'").toList());
			payouts = jdbc.sql("""
					select p.id, p.specialist_account_id, d.display_hint, p.amount_minor, p.currency,
					 p.payout_provider, p.status, count(i.earning_id)::integer earning_count,
					 max(a.provider_payout_reference) provider_reference, p.last_failure_code,
					 p.requested_at, p.completed_at
					from specialist_payout p
					join specialist_payout_destination d on d.id=p.destination_id
					join specialist_payout_item i on i.payout_id=p.id
					left join specialist_payout_attempt a on a.payout_id=p.id
					 and a.attempt_number=(select max(latest.attempt_number) from specialist_payout_attempt latest where latest.payout_id=p.id)
					where p.id in (""" + inClause + """
					)
					group by p.id, d.display_hint
					order by p.requested_at desc
					""").query((row, ignored) -> new AdminPayoutResponse.Item(
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
					.list();
		}

		boolean found = subscriptionSummary != null || !payments.isEmpty() || !credits.isEmpty()
				|| !appointments.isEmpty() || !earnings.isEmpty() || !payouts.isEmpty();

		return new AdminCommercialProvenanceResponse(
				clock.instant(),
				queryType,
				queryValue,
				found,
				subscriptionSummary,
				payments,
				credits,
				appointments,
				disputes,
				earnings,
				payouts);
	}

	private static Instant instant(ResultSet row, String column) throws SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private record Pair(UUID first, UUID second) {
	}

	private record PairRef(String sourceRef, UUID appointmentId) {
	}
}

package com.mentalbridge.consultation.reconciliation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
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
				select * from (
				select p1.id, p1.order_id, 'MOMO' as provider, p1.trans_id, p1.processing_status, p1.failure_code, p1.received_at,
				 case when p1.trans_id > 0 and exists (select 1 from momo_payment_ipn p2 where p2.id <> p1.id and p2.trans_id = p1.trans_id and p2.order_id <> p1.order_id) then 'DUPLICATE_TRANS_ID'
				      when p1.failure_code = 'PAYMENT_ALREADY_FINAL' or exists (select 1 from momo_payment_ipn p2 where p2.id <> p1.id and p2.order_id = p1.order_id and p2.request_id = p1.request_id and p2.result_code = p1.result_code) then 'DUPLICATE_DELIVERY'
				      when p1.processing_status = 'RECEIVED' then 'PENDING_PROCESSING'
				      when p1.processing_status = 'VERIFIED_UNMATCHED' then 'VERIFIED_UNMATCHED'
				      when p1.processing_status = 'PROCESSING_FAILED' or p1.failure_code in ('MOMO_PAYMENT_FAILED', 'PAYMENT_EXPIRED') then 'PROCESSING_FAILED'
				      else 'DISCREPANCY' end as discrepancy_type
				from momo_payment_ipn p1
				where (p1.processing_status in ('RECEIVED', 'VERIFIED_UNMATCHED', 'PROCESSING_FAILED'))
				   or (p1.failure_code in ('PAYMENT_ALREADY_FINAL', 'MOMO_PAYMENT_FAILED', 'PAYMENT_EXPIRED'))
				   or (p1.trans_id > 0 and exists (select 1 from momo_payment_ipn p2 where p2.id <> p1.id and p2.trans_id = p1.trans_id and p2.order_id <> p1.order_id))
				   or (exists (select 1 from momo_payment_ipn p2 where p2.id <> p1.id and p2.order_id = p1.order_id and p2.request_id = p1.request_id and p2.result_code = p1.result_code))
				union all
				select p1.id, p1.order_code::text as order_id, 'PAYOS' as provider, null::bigint as trans_id,
				 p1.processing_status, p1.failure_code, p1.received_at,
				 case when p1.failure_code = 'REFERENCE_CONFLICT' then 'DUPLICATE_TRANS_ID'
				      when p1.processing_status = 'RECEIVED' then 'PENDING_PROCESSING'
				      when p1.processing_status = 'VERIFIED_UNMATCHED' then 'VERIFIED_UNMATCHED'
				      when p1.processing_status = 'PROCESSING_FAILED' then 'PROCESSING_FAILED'
				      else 'DISCREPANCY' end as discrepancy_type
				from payos_payment_webhook p1
				where p1.processing_status in ('RECEIVED', 'VERIFIED_UNMATCHED', 'PROCESSING_FAILED')
				) receipts order by received_at desc, id desc limit 50
				""").query((row, ignored) -> new AdminCommercialDiscrepancyResponse.WebhookDiscrepancy(
						row.getObject("id", UUID.class),
						row.getString("order_id"),
						row.getString("provider"),
						row.getObject("trans_id", Long.class),
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
				order by p.requested_at desc, p.id desc limit 50
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
				order by e.earned_at desc, e.id desc limit 50
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
				order by opened_at desc, id desc limit 50
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

		int totalWebhooks = jdbc.sql("""
				select (select count(*) from momo_payment_ipn p1
				where (p1.processing_status in ('RECEIVED', 'VERIFIED_UNMATCHED', 'PROCESSING_FAILED'))
				   or (p1.failure_code in ('PAYMENT_ALREADY_FINAL', 'MOMO_PAYMENT_FAILED', 'PAYMENT_EXPIRED'))
				   or (p1.trans_id > 0 and exists (select 1 from momo_payment_ipn p2 where p2.id <> p1.id and p2.trans_id = p1.trans_id and p2.order_id <> p1.order_id))
				   or (exists (select 1 from momo_payment_ipn p2 where p2.id <> p1.id and p2.order_id = p1.order_id and p2.request_id = p1.request_id and p2.result_code = p1.result_code)))
				 + (select count(*) from payos_payment_webhook
				    where processing_status in ('RECEIVED', 'VERIFIED_UNMATCHED', 'PROCESSING_FAILED'))
				""").query(Integer.class).single();

		int totalPayouts = jdbc.sql("""
				select count(*) from specialist_payout p
				where p.status in ('FAILED', 'UNKNOWN') or p.last_failure_code is not null
				""").query(Integer.class).single();

		int totalHeld = jdbc.sql("""
				select count(*) from specialist_earning e
				where e.status = 'PENDING_SETTLEMENT'
				   or exists (select 1 from appointment_dispute d where d.appointment_id=e.appointment_id and d.status='OPEN')
				""").query(Integer.class).single();

		int totalDisputes = jdbc.sql("""
				select count(*) from appointment_dispute
				where status = 'OPEN' or resolution_outcome = 'RELEASE_USER_CREDIT'
				""").query(Integer.class).single();

		int total = totalWebhooks + totalPayouts + totalHeld + totalDisputes;
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

		long provided = (paymentId != null ? 1L : 0)
				+ (subscriptionId != null ? 1 : 0)
				+ (creditId != null ? 1 : 0)
				+ (appointmentId != null ? 1 : 0)
				+ (earningId != null ? 1 : 0)
				+ (payoutId != null ? 1 : 0);
		if (provided == 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_PROVENANCE_IDENTIFIER",
					"Exactly one identifier must be provided (paymentId, subscriptionId, creditId, appointmentId, earningId, or payoutId)");
		}
		if (provided > 1) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "AMBIGUOUS_PROVENANCE_IDENTIFIER",
					"Exactly one identifier must be provided; multiple identifiers are not permitted");
		}

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
		} else {
			queryType = "PAYOUT";
			queryValue = payoutId.toString();
		}

		Set<UUID> subscriptionIds = new LinkedHashSet<>();
		if (subscriptionId != null) subscriptionIds.add(subscriptionId);

		Set<UUID> paymentIds = new LinkedHashSet<>();
		if (paymentId != null) {
			paymentIds.add(paymentId);
			var subId = jdbc.sql("select subscription_id from payment_transaction where id=:id")
					.param("id", paymentId).query(UUID.class).optional().orElse(null);
			if (subId != null) subscriptionIds.add(subId);
		}

		Set<UUID> payoutIds = new LinkedHashSet<>();
		if (payoutId != null) payoutIds.add(payoutId);

		Set<UUID> earningIds = new LinkedHashSet<>();
		if (earningId != null) earningIds.add(earningId);

		Set<UUID> appointmentIds = new LinkedHashSet<>();
		if (appointmentId != null) appointmentIds.add(appointmentId);

		Set<UUID> creditIds = new LinkedHashSet<>();
		if (creditId != null) creditIds.add(creditId);

		boolean intermediateTruncated = false;

		if ("PAYOUT".equals(queryType)) {
			payoutIds.add(payoutId);
			final int FETCH_LIMIT = 100;
			var relEarnings = jdbc.sql("select earning_id from specialist_payout_item where payout_id = :payoutId order by earning_id limit " + (FETCH_LIMIT + 1))
					.param("payoutId", payoutId).query(UUID.class).list();
			if (relEarnings.size() > FETCH_LIMIT) intermediateTruncated = true;
			earningIds.addAll(relEarnings.subList(0, Math.min(relEarnings.size(), FETCH_LIMIT)));

			if (!earningIds.isEmpty()) {
				var inClause = inClause(earningIds);
				var rows = jdbc.sql("select appointment_id, consumed_credit_id from specialist_earning where id in (" + inClause + ")")
						.query((row, ignored) -> new Pair(row.getObject("appointment_id", UUID.class),
								row.getObject("consumed_credit_id", UUID.class)))
						.list();
				for (var r : rows) {
					if (r.first != null) appointmentIds.add(r.first);
					if (r.second != null) creditIds.add(r.second);
				}
			}

			if (resolveOriginatingSubscriptions(creditIds, subscriptionIds)) intermediateTruncated = true;
			if (resolveSubscriptionPayments(subscriptionIds, paymentIds)) intermediateTruncated = true;

		} else if ("EARNING".equals(queryType)) {
			earningIds.add(earningId);
			var relPayouts = jdbc.sql("select payout_id from specialist_payout_item where earning_id = :earningId")
					.param("earningId", earningId).query(UUID.class).list();
			payoutIds.addAll(relPayouts);

			var row = jdbc.sql("select appointment_id, consumed_credit_id from specialist_earning where id = :earningId")
					.param("earningId", earningId)
					.query((r, ignored) -> new Pair(r.getObject("appointment_id", UUID.class),
							r.getObject("consumed_credit_id", UUID.class)))
					.optional().orElse(null);
			if (row != null) {
				if (row.first != null) appointmentIds.add(row.first);
				if (row.second != null) creditIds.add(row.second);
			}

			if (resolveOriginatingSubscriptions(creditIds, subscriptionIds)) intermediateTruncated = true;
			if (resolveSubscriptionPayments(subscriptionIds, paymentIds)) intermediateTruncated = true;

		} else if ("APPOINTMENT".equals(queryType)) {
			appointmentIds.add(appointmentId);
			var cId = jdbc.sql("select service_credit_id from appointment where id = :appointmentId")
					.param("appointmentId", appointmentId).query(UUID.class).optional().orElse(null);
			if (cId != null) creditIds.add(cId);

			if (resolveOriginatingSubscriptions(creditIds, subscriptionIds)) intermediateTruncated = true;
			if (resolveSubscriptionPayments(subscriptionIds, paymentIds)) intermediateTruncated = true;

			var apptEarnings = jdbc.sql("select id from specialist_earning where appointment_id = :appointmentId")
					.param("appointmentId", appointmentId).query(UUID.class).list();
			earningIds.addAll(apptEarnings);

			if (!earningIds.isEmpty()) {
				var inClause = inClause(earningIds);
				var pIds = jdbc.sql("select payout_id from specialist_payout_item where earning_id in (" + inClause + ")")
						.query(UUID.class).list();
				payoutIds.addAll(pIds);
			}

		} else if ("CREDIT".equals(queryType)) {
			creditIds.add(creditId);
			if (resolveOriginatingSubscriptions(creditIds, subscriptionIds)) intermediateTruncated = true;
			if (resolveSubscriptionPayments(subscriptionIds, paymentIds)) intermediateTruncated = true;
			if (resolveCreditAppointments(creditIds, appointmentIds)) intermediateTruncated = true;
			if (resolveAppointmentEarnings(appointmentIds, earningIds)) intermediateTruncated = true;
			if (resolveEarningPayouts(earningIds, payoutIds)) intermediateTruncated = true;

		} else if ("PAYMENT".equals(queryType)) {
			paymentIds.add(paymentId);
			final int FETCH_LIMIT = 100;
			var directCredits = jdbc.sql("""
					select c.id
					from payment_transaction pt
					join user_subscription s on s.id = pt.subscription_id
					join service_credit_period p on p.source = 'PAID'
					  and p.source_reference = pt.subscription_id::text
					  and p.account_id = s.user_id
					  and p.plan_version = pt.target_plan_version_id::text
					  and p.period_start = pt.paid_at
					join service_credit c on c.period_id = p.id
					where pt.id = :paymentId and pt.status = 'SUCCEEDED'
					  and not exists (
					    select 1 from payment_transaction other
					    where other.id <> pt.id and other.subscription_id = pt.subscription_id
					      and other.target_plan_version_id = pt.target_plan_version_id
					      and other.status = 'SUCCEEDED' and other.paid_at = pt.paid_at
					  )
					order by c.created_at, c.id
					limit %d
					""".formatted(FETCH_LIMIT + 1))
					.param("paymentId", paymentId).query(UUID.class).list();

			if (directCredits.size() > FETCH_LIMIT) {
				intermediateTruncated = true;
				directCredits = directCredits.subList(0, FETCH_LIMIT);
			}
			creditIds.addAll(directCredits);

			if (resolveCreditAppointments(creditIds, appointmentIds)) intermediateTruncated = true;
			if (resolveAppointmentEarnings(appointmentIds, earningIds)) intermediateTruncated = true;
			if (resolveEarningPayouts(earningIds, payoutIds)) intermediateTruncated = true;
		} else if ("SUBSCRIPTION".equals(queryType)) {
			subscriptionIds.add(subscriptionId);
			if (resolveSubscriptionPayments(subscriptionIds, paymentIds)) intermediateTruncated = true;

			final int FETCH_LIMIT = 100;
			if (!subscriptionIds.isEmpty()) {
				var inClause = inClause(subscriptionIds);
				var subCredits = jdbc.sql("""
						select c.id
						from service_credit c
						join service_credit_period p on p.id=c.period_id
						where p.source = 'PAID' and p.source_reference in (""" + inClause + """
						) order by c.created_at, c.id limit """ + " " + (FETCH_LIMIT + 1))
						.query(UUID.class).list();

			int subCreditsSize = subCredits.size();
			if (subCreditsSize > FETCH_LIMIT) {
					intermediateTruncated = true;
					subCredits = subCredits.subList(0, FETCH_LIMIT);
				}

				creditIds.addAll(subCredits);
			}

			if (resolveCreditAppointments(creditIds, appointmentIds)) intermediateTruncated = true;
			if (resolveAppointmentEarnings(appointmentIds, earningIds)) intermediateTruncated = true;
			if (resolveEarningPayouts(earningIds, payoutIds)) intermediateTruncated = true;
		}

		final int FETCH_LIMIT = 100;

		// Load subscriptions
		List<AdminCommercialProvenanceResponse.AdminSubscriptionSummary> subscriptions = Collections.emptyList();
		if (!subscriptionIds.isEmpty()) {
			var inClause = inClause(subscriptionIds);
			subscriptions = jdbc.sql("""
					select s.id, s.user_id, s.plan_version_id, v.plan_code, v.pricing_policy_version,
					 s.status, s.current_period_start, s.current_period_end, s.created_at
					from user_subscription s
					join subscription_plan_version v on v.id=s.plan_version_id
					where s.id in (""" + inClause + ") order by s.created_at desc, s.id desc limit " + (FETCH_LIMIT + 1))
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
					.list();
		}

		// Load payments
		List<AdminPaymentResponse.Item> payments = Collections.emptyList();
		if (!paymentIds.isEmpty()) {
			var sql = new StringBuilder("""
					select pt.id, pt.subscription_id, pt.target_plan_version_id, v.plan_code, v.pricing_policy_version,
					 pt.purpose, pt.payment_provider, pt.status, pt.amount_minor, pt.currency,
					 pt.created_at, pt.paid_at, pt.failed_at
					from payment_transaction pt join subscription_plan_version v on v.id=pt.target_plan_version_id
					where 1=0
					""");
			if (!paymentIds.isEmpty()) {
				var inClause = inClause(paymentIds);
				sql.append(" or pt.id in (").append(inClause).append(")");
			}
			if (paymentId != null) {
				sql.append(" order by case when pt.id = '").append(paymentId).append("' then 0 else 1 end,");
			} else {
				sql.append(" order by");
			}
			sql.append(" pt.created_at desc, pt.id desc limit ").append(FETCH_LIMIT + 1);

			payments = jdbc.sql(sql.toString())
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
		}

		// Load credits
		List<AdminCommercialProvenanceResponse.AdminCreditSummary> credits = Collections.emptyList();
		if (!creditIds.isEmpty()) {
			var inClause = inClause(creditIds);
			credits = jdbc.sql("""
					select c.id, c.period_id, c.ordinal, c.state, p.credit_allocation_minor, c.appointment_id, c.created_at
					from service_credit c
					join service_credit_period p on p.id=c.period_id
					where c.id in (""" + inClause + ") order by p.period_start desc, c.ordinal asc, c.id asc limit " + (FETCH_LIMIT + 1))
					.query((row, ignored) -> new AdminCommercialProvenanceResponse.AdminCreditSummary(
							row.getObject("id", UUID.class),
							row.getObject("period_id", UUID.class),
							row.getInt("ordinal"),
							row.getString("state"),
							row.getLong("credit_allocation_minor"),
							row.getObject("appointment_id", UUID.class),
							row.getTimestamp("created_at").toInstant()))
					.list();
		}

		// Load appointments
		List<AdminCommercialProvenanceResponse.AdminAppointmentSummary> appointments = Collections.emptyList();
		if (!appointmentIds.isEmpty()) {
			var inClause = inClause(appointmentIds);
			appointments = jdbc.sql("""
					select id, specialist_account_id, service_credit_id, status, modality, session_outcome,
					 completion_fact_id, scheduled_start_at, scheduled_end_at, session_settled_at
					from appointment where id in (""" + inClause + ") order by scheduled_start_at desc, id desc limit " + (FETCH_LIMIT + 1))
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
			var inClause = inClause(appointmentIds);
			disputes = jdbc.sql("""
					select id, appointment_id, opened_by_role, reason_code, status, resolution_outcome,
					 resolution_reason, opened_at, resolved_at
					from appointment_dispute where appointment_id in (""" + inClause + ") order by opened_at desc, id desc limit " + (FETCH_LIMIT + 1))
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
			var inClause = inClause(earningIds);
			earnings = jdbc.sql("""
					select id, appointment_id, consumed_credit_id, specialist_account_id, plan_version,
					 credit_allocation_minor, specialist_share_bps, specialist_amount_minor,
					 platform_allocation_minor, status, earned_at, settlement_available_at, paid_at
					from specialist_earning where id in (""" + inClause + ") order by earned_at desc, id desc limit " + (FETCH_LIMIT + 1))
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
			var inClause = inClause(payoutIds);
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
					order by p.requested_at desc, p.id desc limit
					""" + (FETCH_LIMIT + 1))
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
					.list();
		}

		boolean anyTruncated = intermediateTruncated
				|| subscriptions.size() > FETCH_LIMIT
				|| payments.size() > FETCH_LIMIT
				|| credits.size() > FETCH_LIMIT
				|| appointments.size() > FETCH_LIMIT
				|| disputes.size() > FETCH_LIMIT
				|| earnings.size() > FETCH_LIMIT
				|| payouts.size() > FETCH_LIMIT;

		if (subscriptions.size() > FETCH_LIMIT) subscriptions = new ArrayList<>(subscriptions.subList(0, FETCH_LIMIT));
		if (payments.size() > FETCH_LIMIT) payments = new ArrayList<>(payments.subList(0, FETCH_LIMIT));
		if (credits.size() > FETCH_LIMIT) credits = new ArrayList<>(credits.subList(0, FETCH_LIMIT));
		if (appointments.size() > FETCH_LIMIT) appointments = new ArrayList<>(appointments.subList(0, FETCH_LIMIT));
		if (disputes.size() > FETCH_LIMIT) disputes = new ArrayList<>(disputes.subList(0, FETCH_LIMIT));
		if (earnings.size() > FETCH_LIMIT) earnings = new ArrayList<>(earnings.subList(0, FETCH_LIMIT));
		if (payouts.size() > FETCH_LIMIT) payouts = new ArrayList<>(payouts.subList(0, FETCH_LIMIT));

		boolean found = !subscriptions.isEmpty() || !payments.isEmpty() || !credits.isEmpty()
				|| !appointments.isEmpty() || !earnings.isEmpty() || !payouts.isEmpty();

		AdminCommercialProvenanceResponse.AdminSubscriptionSummary singleSub =
				subscriptions.isEmpty() ? null : subscriptions.get(0);

		return new AdminCommercialProvenanceResponse(
				clock.instant(),
				queryType,
				queryValue,
				found,
				anyTruncated,
				singleSub,
				subscriptions,
				payments,
				credits,
				appointments,
				disputes,
				earnings,
				payouts);
	}

	private boolean resolveOriginatingSubscriptions(Set<UUID> creditIds, Set<UUID> subscriptionIds) {
		if (creditIds.isEmpty()) return false;
		final int FETCH_LIMIT = 100;
		var inClause = inClause(creditIds);
		var creditRows = jdbc.sql("""
				select c.id, p.source, p.source_reference
				from service_credit c join service_credit_period p on p.id=c.period_id
				where c.id in (""" + inClause + ") order by c.id limit " + (FETCH_LIMIT + 1))
				.query((row, ignored) -> new CreditPeriodRow(
						row.getObject("id", UUID.class),
						null,
						row.getString("source"),
						row.getString("source_reference")))
				.list();

		boolean wasTruncated = creditRows.size() > FETCH_LIMIT;
		int limitedSize = Math.min(creditRows.size(), FETCH_LIMIT);
		for (int i = 0; i < limitedSize; i++) {
			var cr = creditRows.get(i);
			if ("PAID".equals(cr.source) && cr.sourceReference != null) {
				try {
					subscriptionIds.add(UUID.fromString(cr.sourceReference));
				} catch (IllegalArgumentException ignored) {
				}
			}
		}

		var upgradeSubs = jdbc.sql("""
				select distinct u.subscription_id from subscription_upgrade_credit uc
				join subscription_upgrade u on u.id=uc.upgrade_id
				where uc.credit_id in (""" + inClause + ") order by u.subscription_id limit " + (FETCH_LIMIT + 1))
				.query(UUID.class).list();
		if (upgradeSubs.size() > FETCH_LIMIT) wasTruncated = true;
		limitedSize = Math.min(upgradeSubs.size(), FETCH_LIMIT);
		subscriptionIds.addAll(upgradeSubs.subList(0, limitedSize));
		return wasTruncated;
	}

	private boolean resolveSubscriptionPayments(Set<UUID> subscriptionIds, Set<UUID> paymentIds) {
		if (subscriptionIds.isEmpty()) return false;
		final int FETCH_LIMIT = 100;
		var inClause = inClause(subscriptionIds);
		var subPaymentIds = jdbc.sql("select id from payment_transaction where subscription_id in (" + inClause + ") order by created_at desc, id desc limit " + (FETCH_LIMIT + 1))
				.query(UUID.class).list();
		boolean wasTruncated = subPaymentIds.size() > FETCH_LIMIT;
		int limitedSize = Math.min(subPaymentIds.size(), FETCH_LIMIT);
		paymentIds.addAll(subPaymentIds.subList(0, limitedSize));
		return wasTruncated;
	}

	private boolean resolveCreditAppointments(Set<UUID> creditIds, Set<UUID> appointmentIds) {
		if (creditIds.isEmpty()) return false;
		final int FETCH_LIMIT = 100;
		var inClause = inClause(creditIds);
		var sql = """
				select a.id from appointment a
				where a.service_credit_id in (%s)
				order by
				  case
				    when a.id in (select c.appointment_id from service_credit c where c.id in (%s) and c.appointment_id is not null) then 0
				    when a.status = 'COMPLETED' then 1
				    else 2
				  end,
				  a.scheduled_start_at desc,
				  a.id desc
				limit %d
				""".formatted(inClause, inClause, FETCH_LIMIT + 1);
		var relatedIds = jdbc.sql(sql).query(UUID.class).list();
		appointmentIds.addAll(relatedIds.subList(0, Math.min(relatedIds.size(), FETCH_LIMIT)));
		return relatedIds.size() > FETCH_LIMIT;
	}

	private boolean resolveAppointmentEarnings(Set<UUID> appointmentIds, Set<UUID> earningIds) {
		if (appointmentIds.isEmpty()) return false;
		final int FETCH_LIMIT = 100;
		var relatedIds = jdbc.sql("select id from specialist_earning where appointment_id in (" + inClause(appointmentIds)
				+ ") order by earned_at, id limit " + (FETCH_LIMIT + 1))
				.query(UUID.class).list();
		earningIds.addAll(relatedIds.subList(0, Math.min(relatedIds.size(), FETCH_LIMIT)));
		return relatedIds.size() > FETCH_LIMIT;
	}

	private boolean resolveEarningPayouts(Set<UUID> earningIds, Set<UUID> payoutIds) {
		if (earningIds.isEmpty()) return false;
		final int FETCH_LIMIT = 100;
		var relatedIds = jdbc.sql("select distinct payout_id from specialist_payout_item where earning_id in (" + inClause(earningIds)
				+ ") order by payout_id limit " + (FETCH_LIMIT + 1))
				.query(UUID.class).list();
		payoutIds.addAll(relatedIds.subList(0, Math.min(relatedIds.size(), FETCH_LIMIT)));
		return relatedIds.size() > FETCH_LIMIT;
	}

	private static String inClause(Collection<UUID> ids) {
		return String.join(",", ids.stream().map(id -> "'" + id + "'").toList());
	}

	private static Instant instant(ResultSet row, String column) throws SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private record Pair(UUID first, UUID second) {
	}

	private record CreditPeriodRow(UUID creditId, UUID appointmentId, String source, String sourceReference) {
	}
}

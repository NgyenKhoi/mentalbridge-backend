package com.mentalbridge.consultation.earnings;

import java.time.Clock;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.shared.ApiException;

@Service
public class SpecialistPayoutService {

	private final JdbcClient jdbc;
	private final PayoutProperties properties;
	private final PayoutDestinationService destinations;
	private final PayoutTransactions transactions;
	private final PayoutProvider provider;
	private final Clock clock;

	public SpecialistPayoutService(JdbcClient jdbc, PayoutProperties properties, PayoutDestinationService destinations,
			PayoutTransactions transactions, PayoutProvider provider, Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.destinations = destinations;
		this.transactions = transactions;
		this.provider = provider;
		this.clock = clock;
	}

	public SpecialistEarningsResponse.Destination saveDestination(UUID specialistId, SavePayoutDestinationRequest request) {
		assertApprovedSpecialist(specialistId);
		return destinations.save(specialistId, request);
	}

	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
	public SpecialistEarningsResponse requestPayout(UUID specialistId, UUID destinationId, String idempotencyKey) {
		assertApprovedSpecialist(specialistId);
		var prepared = transactions.prepare(specialistId, destinationId, idempotencyKey);
		if (prepared.submit()) {
			var result = provider.submit(prepared.provider(), prepared.command());
			transactions.reconcile(prepared.payoutId(), prepared.attemptId(), result);
		}
		return earnings(specialistId);
	}

	@Transactional
	public SpecialistEarningsResponse earnings(UUID specialistId) {
		assertApprovedSpecialist(specialistId);
		transactions.promoteSettledEarnings(specialistId);
		var totals = jdbc.sql("""
				select
				 coalesce(sum(specialist_amount_minor) filter (where status='PENDING_SETTLEMENT'), 0) pending,
				 coalesce(sum(specialist_amount_minor) filter (where status='AVAILABLE' and not exists (select 1 from specialist_payout_item i where i.earning_id=specialist_earning.id and i.released_at is null)), 0) available,
				 coalesce(sum(specialist_amount_minor) filter (where status='AVAILABLE' and exists (select 1 from specialist_payout_item i where i.earning_id=specialist_earning.id and i.released_at is null)), 0) retry_pending,
				 coalesce(sum(specialist_amount_minor) filter (where status='PROCESSING'), 0) processing,
				 coalesce(sum(specialist_amount_minor) filter (where status='PAID'), 0) paid
				from specialist_earning where specialist_account_id=:specialistId
				""").param("specialistId", specialistId).query((row, ignored) -> new SpecialistEarningsResponse.Balance(
				row.getLong("pending"), row.getLong("available"), row.getLong("processing"), row.getLong("paid"), row.getLong("retry_pending"))).single();
		var destination = destinations.current(specialistId);
		var earnings = jdbc.sql("""
				select id, appointment_id, consumed_credit_id, plan_version, credit_allocation_minor,
				 specialist_share_bps, specialist_amount_minor, status, earned_at, settlement_available_at
				from specialist_earning where specialist_account_id=:specialistId
				order by earned_at desc, id desc limit 100
				""").param("specialistId", specialistId).query((row, ignored) -> new SpecialistEarningsResponse.Earning(
				row.getObject("id", UUID.class), row.getObject("appointment_id", UUID.class),
				row.getObject("consumed_credit_id", UUID.class), row.getString("plan_version"),
				row.getLong("credit_allocation_minor"), row.getInt("specialist_share_bps") / 100,
				row.getLong("specialist_amount_minor"), row.getString("status"),
				row.getTimestamp("earned_at").toInstant(), row.getTimestamp("settlement_available_at").toInstant())).list();
		var payouts = jdbc.sql("""
				select p.id, p.destination_id, p.amount_minor, p.payout_provider, p.status,
				 a.provider_payout_reference, p.last_failure_code, p.requested_at, p.completed_at, p.closed_at,
				 a.reconciliation_status, a.next_reconciliation_at, a.review_required_at
				from specialist_payout p
				left join specialist_payout_attempt a on a.payout_id=p.id
				 and a.attempt_number=(select max(latest.attempt_number) from specialist_payout_attempt latest where latest.payout_id=p.id)
				where p.specialist_account_id=:specialistId
				order by p.requested_at desc, p.id desc limit 100
				""").param("specialistId", specialistId).query((row, ignored) -> new SpecialistEarningsResponse.Payout(
				row.getObject("id", UUID.class), row.getObject("destination_id", UUID.class), row.getLong("amount_minor"),
				row.getString("payout_provider"), row.getString("status"), row.getString("provider_payout_reference"),
				row.getString("last_failure_code"), row.getTimestamp("requested_at").toInstant(),
				instant(row, "completed_at"), row.getString("reconciliation_status"),
				instant(row, "next_reconciliation_at"), instant(row, "review_required_at"), instant(row, "closed_at"))).list();
		return new SpecialistEarningsResponse("VND", SpecialistEarningService.POLICY_VERSION, "DISABLED".equals(properties.getMode()) ? null : properties.getMode(),
				(int) properties.getSettlementHold().toDays(), properties.getMinimumWithdrawalVnd(), clock.instant(),
				totals, destination, earnings, payouts, new SpecialistEarningsResponse.Availability(properties.getMode(),
						properties.ready(), properties.ready() ? null : ("DISABLED".equals(properties.getMode())
								? "REAL_PAYOUT_DISABLED" : "PAYOUT_PROVIDER_UNAVAILABLE")));
	}

	private void assertApprovedSpecialist(UUID specialistId) {
		var approved = jdbc.sql("select approval_status from specialist_profile where account_id=:id")
				.param("id", specialistId).query(String.class).optional();
		if (approved.isEmpty() || !approved.orElseThrow().equals("APPROVED")) {
			throw new ApiException(HttpStatus.FORBIDDEN, "SPECIALIST_EARNINGS_ACCESS_DENIED",
					"Specialist earnings are unavailable");
		}
	}

	@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
	record PayoutDestinationDetails(String accountReference, String accountHolderName, String bankCode) {
	}

	private java.time.Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}

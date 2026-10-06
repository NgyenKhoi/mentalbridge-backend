package com.mentalbridge.consultation.earnings;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
	private final PayoutDestinationCipher cipher;
	private final PayoutTransactions transactions;
	private final PayoutProvider provider;
	private final Clock clock;

	public SpecialistPayoutService(JdbcClient jdbc, PayoutProperties properties, PayoutDestinationCipher cipher,
			PayoutTransactions transactions, PayoutProvider provider, Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.cipher = cipher;
		this.transactions = transactions;
		this.provider = provider;
		this.clock = clock;
	}

	@Transactional
	public SpecialistEarningsResponse.Destination saveDestination(UUID specialistId, SavePayoutDestinationRequest request) {
		assertApprovedSpecialist(specialistId);
		var reference = request.accountReference().trim();
		if (!reference.matches("[0-9]{6,32}")) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "PAYOUT_DESTINATION_INVALID",
					"Payout destination must contain 6 to 32 digits");
		}
		var providerName = properties.getMode().equals("FAKE") ? "FAKE" : "MOMO";
		if (!providerName.equals("FAKE")) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "REAL_PAYOUT_DISABLED",
					"Real payout is not enabled");
		}
		var fingerprint = cipher.fingerprint(request.destinationType() + ":" + reference);
		var existing = jdbc.sql("""
				select id from specialist_payout_destination
				where specialist_account_id=:specialistId and destination_fingerprint=:fingerprint
				""").param("specialistId", specialistId).param("fingerprint", fingerprint).query(UUID.class).optional();
		if (existing.isPresent()) return destination(specialistId, existing.orElseThrow());
		var now = clock.instant();
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_payout_destination (
				 id, specialist_account_id, payout_provider, destination_type, destination_ciphertext,
				 encryption_key_version, destination_fingerprint, display_hint, status,
				 verified_at, created_at, updated_at
				) values (:id, :specialistId, :provider, :type, :ciphertext,
				 :keyVersion, :fingerprint, :hint, 'VERIFIED', :now, :now, :now)
				""").param("id", id).param("specialistId", specialistId).param("provider", providerName)
				.param("type", request.destinationType()).param("ciphertext", cipher.encrypt(reference))
				.param("keyVersion", properties.getEncryptionKeyVersion()).param("fingerprint", fingerprint)
				.param("hint", mask(reference)).param("now", databaseInstant(now)).update();
		return destination(specialistId, id);
	}

	public SpecialistEarningsResponse requestPayout(UUID specialistId, UUID destinationId, String idempotencyKey) {
		assertApprovedSpecialist(specialistId);
		var prepared = transactions.prepare(specialistId, destinationId, idempotencyKey);
		if (prepared.submit()) {
			var result = provider.submit(prepared.payoutId(), prepared.attemptId(), prepared.amountVnd(), prepared.currency());
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
				 coalesce(sum(specialist_amount_minor) filter (where status='AVAILABLE'), 0) available,
				 coalesce(sum(specialist_amount_minor) filter (where status='PROCESSING'), 0) processing,
				 coalesce(sum(specialist_amount_minor) filter (where status='PAID'), 0) paid
				from specialist_earning where specialist_account_id=:specialistId
				""").param("specialistId", specialistId).query((row, ignored) -> new SpecialistEarningsResponse.Balance(
				row.getLong("pending"), row.getLong("available"), row.getLong("processing"), row.getLong("paid"))).single();
		var destination = jdbc.sql("""
				select id from specialist_payout_destination
				where specialist_account_id=:specialistId and status='VERIFIED'
				order by updated_at desc, id desc limit 1
				""").param("specialistId", specialistId).query(UUID.class).optional()
				.map(id -> destination(specialistId, id)).orElse(null);
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
				 a.provider_payout_reference, p.last_failure_code, p.requested_at, p.completed_at
				from specialist_payout p
				left join specialist_payout_attempt a on a.payout_id=p.id and a.attempt_number=1
				where p.specialist_account_id=:specialistId
				order by p.requested_at desc, p.id desc limit 100
				""").param("specialistId", specialistId).query((row, ignored) -> new SpecialistEarningsResponse.Payout(
				row.getObject("id", UUID.class), row.getObject("destination_id", UUID.class), row.getLong("amount_minor"),
				row.getString("payout_provider"), row.getString("status"), row.getString("provider_payout_reference"),
				row.getString("last_failure_code"), row.getTimestamp("requested_at").toInstant(),
				instant(row, "completed_at"))).list();
		return new SpecialistEarningsResponse("VND", SpecialistEarningService.POLICY_VERSION,
				(int) properties.getSettlementHold().toDays(), properties.getMinimumWithdrawalVnd(), clock.instant(),
				totals, destination, earnings, payouts);
	}

	private SpecialistEarningsResponse.Destination destination(UUID specialistId, UUID id) {
		return jdbc.sql("""
				select id, payout_provider, destination_type, display_hint, status, verified_at
				from specialist_payout_destination where id=:id and specialist_account_id=:specialistId
				""").param("id", id).param("specialistId", specialistId)
				.query((row, ignored) -> new SpecialistEarningsResponse.Destination(row.getObject("id", UUID.class),
						row.getString("payout_provider"), row.getString("destination_type"), row.getString("display_hint"),
						row.getString("status"), row.getTimestamp("verified_at").toInstant())).single();
	}

	private void assertApprovedSpecialist(UUID specialistId) {
		var approved = jdbc.sql("select approval_status from specialist_profile where account_id=:id")
				.param("id", specialistId).query(String.class).optional();
		if (approved.isEmpty() || !approved.orElseThrow().equals("APPROVED")) {
			throw new ApiException(HttpStatus.FORBIDDEN, "SPECIALIST_EARNINGS_ACCESS_DENIED",
					"Specialist earnings are unavailable");
		}
	}

	private String mask(String value) {
		return "\u2022\u2022\u2022\u2022 " + value.substring(value.length() - 4);
	}

	private java.time.Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private OffsetDateTime databaseInstant(java.time.Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}

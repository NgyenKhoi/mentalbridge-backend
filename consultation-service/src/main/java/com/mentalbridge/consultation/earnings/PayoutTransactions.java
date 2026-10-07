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
class PayoutTransactions {

	private final JdbcClient jdbc;
	private final PayoutProperties properties;
	private final Clock clock;

	PayoutTransactions(JdbcClient jdbc, PayoutProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	PreparedPayout prepare(UUID specialistId, UUID destinationId, String idempotencyKey) {
		promoteSettledEarnings(specialistId);
		var replay = jdbc.sql("""
				select p.id, p.amount_minor, p.currency, p.status
				from specialist_payout p
				where p.specialist_account_id=:specialistId and p.idempotency_key=:key
				for update
				""").param("specialistId", specialistId).param("key", idempotencyKey)
				.query((row, ignored) -> new ExistingPayout(row.getObject("id", UUID.class),
						row.getLong("amount_minor"), row.getString("currency"), row.getString("status")))
				.optional();
		if (replay.isPresent()) return replay(replay.orElseThrow());
		var alreadyRequestedToday = jdbc.sql("""
				select count(*) from specialist_payout
				where specialist_account_id=:specialistId and requested_on=:requestedOn
				""").param("specialistId", specialistId).param("requestedOn", java.time.LocalDate.now(clock))
				.query(Long.class).single();
		if (alreadyRequestedToday > 0) {
			throw new ApiException(HttpStatus.CONFLICT, "PAYOUT_DAILY_LIMIT_REACHED",
					"Only one payout request is allowed per day");
		}

		var provider = properties.getMode().equals("FAKE") ? "FAKE" : "MOMO";
		var destination = jdbc.sql("""
				select id, destination_type, destination_ciphertext from specialist_payout_destination
				where id=:destinationId and specialist_account_id=:specialistId
				  and payout_provider=:provider and status='VERIFIED'
				for update
				""").param("destinationId", destinationId).param("specialistId", specialistId)
				.param("provider", provider).query((row, ignored) -> new Destination(
						row.getObject("id", UUID.class), row.getString("destination_type"),
						row.getString("destination_ciphertext"))).optional()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYOUT_DESTINATION_NOT_FOUND",
					"Payout destination was not found"));
		var earnings = jdbc.sql("""
				select id, specialist_amount_minor from specialist_earning
				where specialist_account_id=:specialistId and status='AVAILABLE'
				order by earned_at, id for update
				""").param("specialistId", specialistId)
				.query((row, ignored) -> new EarningAmount(row.getObject("id", UUID.class),
						row.getLong("specialist_amount_minor"))).list();
		var amount = earnings.stream().mapToLong(EarningAmount::amount).sum();
		if (amount < properties.getMinimumWithdrawalVnd()) {
			throw new ApiException(HttpStatus.CONFLICT, "PAYOUT_MINIMUM_NOT_REACHED",
					"Available earnings have not reached the minimum withdrawal amount");
		}
		var now = clock.instant();
		var payoutId = UUID.randomUUID();
		var attemptId = UUID.randomUUID();
		try {
			jdbc.sql("""
					insert into specialist_payout (
					 id, specialist_account_id, destination_id, currency, amount_minor, payout_provider,
					 status, idempotency_key, requested_on, requested_at, created_at, updated_at
					) values (:id, :specialistId, :destinationId, 'VND', :amount, :provider,
					 'PROCESSING', :key, :requestedOn, :now, :now, :now)
					""").param("id", payoutId).param("specialistId", specialistId).param("destinationId", destination.id())
					.param("amount", amount).param("provider", provider).param("key", idempotencyKey)
					.param("requestedOn", java.time.LocalDate.now(clock)).param("now", databaseInstant(now)).update();
		}
		catch (org.springframework.dao.DuplicateKeyException exception) {
			throw new ApiException(HttpStatus.CONFLICT, "PAYOUT_DAILY_LIMIT_REACHED",
					"Only one payout request is allowed per day");
		}
		var providerKey = payoutId + ":1";
		for (var earning : earnings) {
			jdbc.sql("insert into specialist_payout_item (payout_id, earning_id, amount_minor) values (:payoutId, :earningId, :amount)")
					.param("payoutId", payoutId).param("earningId", earning.id()).param("amount", earning.amount()).update();
		}
		jdbc.sql("update specialist_earning set status='PROCESSING', updated_at=:now, version=version+1 where specialist_account_id=:specialistId and status='AVAILABLE'")
				.param("now", databaseInstant(now)).param("specialistId", specialistId).update();
		jdbc.sql("""
				insert into specialist_payout_attempt (
				 id, payout_id, payout_provider, attempt_number, provider_idempotency_key,
				 status, requested_at, created_at, updated_at
				) values (:id, :payoutId, :provider, 1, :providerKey, 'PROCESSING', :now, :now, :now)
				""").param("id", attemptId).param("payoutId", payoutId).param("provider", provider)
					.param("providerKey", providerKey).param("now", databaseInstant(now)).update();
		insertHistory(payoutId, null, "PROCESSING", "SPECIALIST_REQUESTED", now);
		return new PreparedPayout(payoutId, attemptId, amount, "VND", true,
				new PayoutProvider.Command(payoutId, attemptId, providerKey, amount, "VND",
						destination.destinationType(), destination.destinationCiphertext()));
	}

	private PreparedPayout replay(ExistingPayout payout) {
		if (!payout.status().equals("FAILED")) {
			var attemptId = jdbc.sql("""
					select id from specialist_payout_attempt
					where payout_id=:payoutId order by attempt_number desc limit 1
					""").param("payoutId", payout.id()).query(UUID.class).single();
			return new PreparedPayout(payout.id(), attemptId, payout.amount(), payout.currency(), false, null);
		}

		var earnings = jdbc.sql("""
				select e.id, e.status from specialist_payout_item i
				join specialist_earning e on e.id=i.earning_id
				where i.payout_id=:payoutId
				order by e.id for update of e
				""").param("payoutId", payout.id())
				.query((row, ignored) -> new EarningStatus(row.getObject("id", UUID.class), row.getString("status")))
				.list();
		if (earnings.isEmpty() || earnings.stream().anyMatch(earning -> !earning.status().equals("AVAILABLE"))) {
			throw new ApiException(HttpStatus.CONFLICT, "PAYOUT_RETRY_NOT_AVAILABLE",
					"Failed payout is not eligible for another provider attempt");
		}

		var attemptNumber = jdbc.sql("""
				select coalesce(max(attempt_number), 0) + 1
				from specialist_payout_attempt where payout_id=:payoutId
				""").param("payoutId", payout.id()).query(Integer.class).single();
		var attemptId = UUID.randomUUID();
		var providerKey = payout.id() + ":" + attemptNumber;
		var destination = destinationForPayout(payout.id());
		var now = clock.instant();
		var restored = jdbc.sql("""
				update specialist_earning set status='PROCESSING', updated_at=:now, version=version+1
				where id in (select earning_id from specialist_payout_item where payout_id=:payoutId)
				  and status='AVAILABLE'
				""").param("now", databaseInstant(now)).param("payoutId", payout.id()).update();
		if (restored != earnings.size()) {
			throw new ApiException(HttpStatus.CONFLICT, "PAYOUT_RETRY_NOT_AVAILABLE",
					"Failed payout is not eligible for another provider attempt");
		}
		jdbc.sql("""
				insert into specialist_payout_attempt (
				 id, payout_id, payout_provider, attempt_number, provider_idempotency_key,
				 status, requested_at, created_at, updated_at
				) values (:id, :payoutId, :provider, :attemptNumber, :providerKey,
				 'PROCESSING', :now, :now, :now)
				""").param("id", attemptId).param("payoutId", payout.id())
				.param("provider", properties.getMode().equals("FAKE") ? "FAKE" : "MOMO")
				.param("attemptNumber", attemptNumber).param("providerKey", providerKey)
				.param("now", databaseInstant(now)).update();
		jdbc.sql("""
				update specialist_payout set status='PROCESSING', last_failure_code=null,
				 completed_at=null, updated_at=:now, version=version+1 where id=:payoutId
				""").param("now", databaseInstant(now)).param("payoutId", payout.id()).update();
		insertHistory(payout.id(), "FAILED", "PROCESSING", "SPECIALIST_RETRIED", now);
		return new PreparedPayout(payout.id(), attemptId, payout.amount(), payout.currency(), true,
				new PayoutProvider.Command(payout.id(), attemptId, providerKey, payout.amount(), payout.currency(),
						destination.destinationType(), destination.destinationCiphertext()));
	}

	private Destination destinationForPayout(UUID payoutId) {
		return jdbc.sql("""
				select d.id, d.destination_type, d.destination_ciphertext
				from specialist_payout p join specialist_payout_destination d on d.id=p.destination_id
				where p.id=:payoutId
				""").param("payoutId", payoutId).query((row, ignored) -> new Destination(
				row.getObject("id", UUID.class), row.getString("destination_type"),
				row.getString("destination_ciphertext"))).single();
	}

	@Transactional
	void reconcile(UUID payoutId, UUID attemptId, PayoutProvider.Result result) {
		var current = jdbc.sql("select status from specialist_payout where id=:id for update")
				.param("id", payoutId).query(String.class).single();
		if (current.equals("SUCCEEDED") || current.equals("FAILED")) return;
		reconcileLocked(payoutId, attemptId, current, result);
	}

	@Transactional
	void reconcileMomo(MomoPayoutIpnRequest request, String payloadHash) {
		var target = jdbc.sql("""
				select p.id payout_id, p.status, p.amount_minor, a.id attempt_id
				from specialist_payout_attempt a
				join specialist_payout p on p.id=a.payout_id
				where a.payout_provider='MOMO' and a.provider_idempotency_key=:requestId
				for update of p, a
				""").param("requestId", request.requestId()).query((row, ignored) -> new MomoTarget(
				row.getObject("payout_id", UUID.class), row.getObject("attempt_id", UUID.class),
				row.getString("status"), row.getLong("amount_minor"))).optional()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MOMO_PAYOUT_NOT_FOUND",
						"MoMo payout callback does not match a payout"));
		if (!target.payoutId().toString().equals(request.orderId()) || target.amount() != request.amount()) {
			throw new ApiException(HttpStatus.CONFLICT, "MOMO_PAYOUT_CALLBACK_MISMATCH",
					"MoMo payout callback does not match the expected payout");
		}
		var eventId = request.partnerCode() + ":" + request.requestId() + ":" + request.transId()
				+ ":" + request.resultCode();
		var inserted = jdbc.sql("""
				insert into payout_provider_event (
				 id, payout_attempt_id, payout_provider, provider_event_id, event_type,
				 payload_sha256, processing_status, received_at
				) values (:id, :attemptId, 'MOMO', :eventId, 'PAYOUT_IPN', :hash, 'RECEIVED', :now)
				on conflict (payout_provider, provider_event_id) do nothing returning id
				""").param("id", UUID.randomUUID()).param("attemptId", target.attemptId()).param("eventId", eventId)
				.param("hash", payloadHash).param("now", databaseInstant(clock.instant())).query(UUID.class).optional();
		if (inserted.isEmpty()) return;
		var status = request.resultCode() == 0 ? "SUCCEEDED"
				: request.resultCode() == 7000 || request.resultCode() == 7002 ? "PROCESSING" : "FAILED";
		if (!target.status().equals("SUCCEEDED") && !target.status().equals("FAILED")) {
			reconcileLocked(target.payoutId(), target.attemptId(), target.status(),
					new PayoutProvider.Result(status, request.transId() == 0 ? null : Long.toString(request.transId()),
							status.equals("FAILED") ? "MOMO_" + request.resultCode() : null));
		}
		jdbc.sql("update payout_provider_event set processing_status='PROCESSED', processed_at=:now where id=:id")
				.param("now", databaseInstant(clock.instant())).param("id", inserted.orElseThrow()).update();
	}

	private void reconcileLocked(UUID payoutId, UUID attemptId, String current, PayoutProvider.Result result) {
		var now = clock.instant();
		var terminalAt = result.status().equals("SUCCEEDED") || result.status().equals("FAILED")
				? databaseInstant(now) : null;
		jdbc.sql("""
				update specialist_payout_attempt
				set status=:status, provider_payout_reference=:reference,
				    provider_confirmed_at=case when :status='SUCCEEDED' then :terminalAt else null end,
				    failed_at=case when :status='FAILED' then :terminalAt else null end,
				    failure_code=:failureCode, updated_at=:now, version=version+1
				where id=:attemptId and payout_id=:payoutId
				""").param("status", result.status()).param("reference", result.providerReference())
				.param("terminalAt", terminalAt).param("failureCode", result.failureCode())
				.param("now", databaseInstant(now)).param("attemptId", attemptId).param("payoutId", payoutId).update();
		jdbc.sql("""
				update specialist_payout set status=:status,
				 completed_at=case when :status='SUCCEEDED' then :terminalAt else null end,
				 last_failure_code=:failureCode, updated_at=:now, version=version+1 where id=:payoutId
				""").param("status", result.status()).param("terminalAt", terminalAt)
				.param("failureCode", result.failureCode()).param("now", databaseInstant(now))
				.param("payoutId", payoutId).update();
		if (result.status().equals("SUCCEEDED")) {
			jdbc.sql("""
					update specialist_earning set status='PAID', paid_at=:now, updated_at=:now, version=version+1
					where id in (select earning_id from specialist_payout_item where payout_id=:payoutId)
					  and status='PROCESSING'
					""").param("now", databaseInstant(now)).param("payoutId", payoutId).update();
		}
		else if (result.status().equals("FAILED")) {
			jdbc.sql("""
					update specialist_earning set status='AVAILABLE', updated_at=:now, version=version+1
					where id in (select earning_id from specialist_payout_item where payout_id=:payoutId)
					  and status='PROCESSING'
					""").param("now", databaseInstant(now)).param("payoutId", payoutId).update();
		}
		insertHistory(payoutId, current, result.status(), result.failureCode() == null ? "PROVIDER_CONFIRMED" : result.failureCode(), now);
	}

	void promoteSettledEarnings(UUID specialistId) {
		jdbc.sql("""
				update specialist_earning set status='AVAILABLE', updated_at=:now, version=version+1
				where specialist_account_id=:specialistId and status='PENDING_SETTLEMENT'
				  and settlement_available_at <= :now
				""").param("specialistId", specialistId).param("now", databaseInstant(clock.instant())).update();
	}

	private void insertHistory(UUID payoutId, String from, String to, String reason, java.time.Instant now) {
		jdbc.sql("""
				insert into specialist_payout_status_history (id, payout_id, from_status, to_status, reason_code, changed_at)
				values (:id, :payoutId, :fromStatus, :toStatus, :reason, :now)
				""").param("id", UUID.randomUUID()).param("payoutId", payoutId).param("fromStatus", from)
				.param("toStatus", to).param("reason", reason).param("now", databaseInstant(now)).update();
	}

	private OffsetDateTime databaseInstant(java.time.Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	record PreparedPayout(UUID payoutId, UUID attemptId, long amountVnd, String currency, boolean submit,
			PayoutProvider.Command command) {
	}

	private record Destination(UUID id, String destinationType, String destinationCiphertext) {
	}

	private record EarningAmount(UUID id, long amount) {
	}

	private record EarningStatus(UUID id, String status) {
	}

	private record ExistingPayout(UUID id, long amount, String currency, String status) {
	}

	private record MomoTarget(UUID payoutId, UUID attemptId, String status, long amount) {
	}
}

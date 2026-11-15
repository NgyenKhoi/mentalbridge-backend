package com.mentalbridge.consultation.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.appointment.AppointmentStatusOutbox;
import com.mentalbridge.consultation.shared.ApiException;

@Service
public class SubscriptionCancellationService {

	private static final String REASON = "OWNER_SUBSCRIPTION_CANCELLED";
	private final JdbcClient jdbc;
	private final Clock clock;
	private final AppointmentStatusOutbox outbox;

	public SubscriptionCancellationService(JdbcClient jdbc, Clock clock, AppointmentStatusOutbox outbox) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.outbox = outbox;
	}

	@Transactional
	public SubscriptionCancellationResponse cancel(UUID actorId, UUID subscriptionId, String idempotencyKey) {
		validateKey(idempotencyKey);
		lock("subscription-cancel-command:" + actorId + ":" + idempotencyKey);
		var fingerprint = fingerprint(subscriptionId);
		var replay = command(actorId, idempotencyKey);
		if (replay != null) {
			if (!MessageDigest.isEqual(replay.fingerprint().getBytes(StandardCharsets.US_ASCII),
					fingerprint.getBytes(StandardCharsets.US_ASCII))) {
				throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key was reused for another subscription");
			}
			return response(owned(actorId, replay.subscriptionId(), false), replay.cancelledAppointments(),
					replay.revokedCredits());
		}

		lock("subscription-cancel:" + subscriptionId);
		var subscription = owned(actorId, subscriptionId, true);
		if (!"ACTIVE".equals(subscription.status())) {
			throw conflict("SUBSCRIPTION_CANCELLATION_INVALID_STATE",
					"Only an active subscription can be cancelled");
		}
		var now = clock.instant();
		var live = liveAppointments(actorId, now);
		if (live.size() > 1) {
			throw conflict("SUBSCRIPTION_LIVE_SESSION_CONFLICT",
					"Cancellation cannot safely select more than one live appointment");
		}
		var liveAppointment = live.isEmpty() ? null : live.getFirst();
		var cancelledAppointments = cancelFutureAppointments(actorId, now);
		var revokedCredits = cancelledAppointments + revokeUnusedCredits(actorId, subscriptionId, now);
		failPendingUpgrade(subscriptionId, now);
		disablePaidEntitlement(actorId, subscriptionId, now);

		var status = liveAppointment == null ? "CANCELLED" : "CANCEL_PENDING_SESSION_END";
		var transitioned = jdbc.sql("""
				update user_subscription set status=:status, ended_at=:now, cancellation_requested_at=:now,
				 cancellation_effective_at=:now, cancellation_live_appointment_id=:liveAppointmentId,
				 updated_at=:now, version=version+1 where id=:subscriptionId and status='ACTIVE'
				""").param("status", status).param("now", database(now))
				.param("liveAppointmentId", liveAppointment == null ? null : liveAppointment.id())
				.param("subscriptionId", subscriptionId).update();
		if (transitioned != 1) throw consistencyConflict();
		jdbc.sql("""
				insert into subscription_status_history
				 (id, subscription_id, from_status, to_status, reason_code, changed_at, changed_by, outcome_code)
				values (:id, :subscriptionId, 'ACTIVE', :status, :reason, :now, :actorId, 'ACCEPTED')
				""").param("id", UUID.randomUUID()).param("subscriptionId", subscriptionId)
				.param("status", status).param("reason", REASON).param("now", database(now))
				.param("actorId", actorId).update();
		jdbc.sql("""
				insert into subscription_cancellation_command
				 (id, actor_account_id, subscription_id, idempotency_key, request_fingerprint, from_status,
				  accepted_status, requested_at, effective_at, live_appointment_id,
				  cancelled_appointment_count, revoked_credit_count, outcome_code)
				values (:id, :actorId, :subscriptionId, :key, :fingerprint, 'ACTIVE', :status, :now, :now,
				 :liveAppointmentId, :cancelledAppointments, :revokedCredits, 'ACCEPTED')
				""").param("id", UUID.randomUUID()).param("actorId", actorId)
				.param("subscriptionId", subscriptionId).param("key", idempotencyKey)
				.param("fingerprint", fingerprint).param("status", status).param("now", database(now))
				.param("liveAppointmentId", liveAppointment == null ? null : liveAppointment.id())
				.param("cancelledAppointments", cancelledAppointments).param("revokedCredits", revokedCredits).update();
		return response(owned(actorId, subscriptionId, false), cancelledAppointments, revokedCredits);
	}

	@Transactional
	public OwnSubscriptionResponse current(UUID actorId) {
		finalizePending(actorId, clock.instant());
		return jdbc.sql("""
				select s.id, v.plan_code, s.status, s.current_period_start, s.current_period_end,
				 s.cancellation_requested_at, s.cancellation_effective_at, s.cancellation_live_appointment_id,
				 a.scheduled_end_at from user_subscription s join subscription_plan_version v on v.id=s.plan_version_id
				 left join appointment a on a.id=s.cancellation_live_appointment_id
				 where s.user_id=:actorId order by s.created_at desc, s.id desc limit 1
				""").param("actorId", actorId).query((row, ignored) -> new OwnSubscriptionResponse(
				row.getObject("id", UUID.class), row.getString("plan_code"), row.getString("status"),
				instant(row, "current_period_start"), instant(row, "current_period_end"),
				instant(row, "cancellation_requested_at"), instant(row, "cancellation_effective_at"),
				row.getObject("cancellation_live_appointment_id", UUID.class), instant(row, "scheduled_end_at"),
				clock.instant())).optional().orElseGet(() -> new OwnSubscriptionResponse(null, "FREE", "NONE",
				null, null, null, null, null, null, clock.instant()));
	}

	@Scheduled(fixedDelayString = "${mentalbridge.consultation.billing.cancellation-finalize-interval:PT1M}")
	@Transactional
	public void finalizePendingCancellations() {
		var now = clock.instant();
		var owners = jdbc.sql("select user_id from user_subscription where status='CANCEL_PENDING_SESSION_END' limit 100")
				.query(UUID.class).list();
		owners.forEach(owner -> finalizePending(owner, now));
	}

	private void finalizePending(UUID actorId, Instant now) {
		var pending = jdbc.sql("""
				select s.id, s.cancellation_live_appointment_id from user_subscription s
				 left join appointment a on a.id=s.cancellation_live_appointment_id
				 where s.user_id=:actorId and s.status='CANCEL_PENDING_SESSION_END'
				 and (a.id is null or a.status not in ('CONFIRMED','IN_PROGRESS') or a.scheduled_end_at<=:now)
				 for update of s
				""").param("actorId", actorId).param("now", database(now))
				.query((row, ignored) -> row.getObject("id", UUID.class)).list();
		for (var subscriptionId : pending) {
			var cancelled = jdbc.sql("""
					update user_subscription set status='CANCELLED', cancellation_live_appointment_id=null,
					 updated_at=:now, version=version+1 where id=:id and status='CANCEL_PENDING_SESSION_END'
					""").param("now", database(now)).param("id", subscriptionId).update();
			if (cancelled != 1) throw consistencyConflict();
			jdbc.sql("""
					insert into subscription_status_history
					 (id, subscription_id, from_status, to_status, reason_code, changed_at, changed_by, outcome_code)
					values (:historyId, :id, 'CANCEL_PENDING_SESSION_END', 'CANCELLED',
					 'LIVE_SESSION_ENDED', :now, :actorId, 'COMPLETED')
					""").param("historyId", UUID.randomUUID()).param("id", subscriptionId)
					.param("now", database(now)).param("actorId", actorId).update();
		}
	}

	private int cancelFutureAppointments(UUID actorId, Instant now) {
		var appointments = jdbc.sql("""
				select id, service_credit_id, status from appointment where user_account_id=:actorId
				 and scheduled_start_at>:now and status in ('REQUESTED','CONFIRMED')
				 order by scheduled_start_at, id for update
				""").param("actorId", actorId).param("now", database(now))
				.query((row, ignored) -> new Appointment(row.getObject("id", UUID.class),
						row.getObject("service_credit_id", UUID.class), row.getString("status"), null)).list();
		for (var appointment : appointments) {
			var cancelled = jdbc.sql("""
					update appointment set status='CANCELLED', cancelled_at=:now, cancelled_by=:actorId,
					 cancellation_reason=:reason, cancellation_credit_outcome='REVOKED', updated_at=:now,
					 version=version+1 where id=:id and status in ('REQUESTED','CONFIRMED')
					""").param("now", database(now)).param("actorId", actorId).param("reason", REASON)
					.param("id", appointment.id()).update();
			if (cancelled != 1) throw consistencyConflict();
			var revokedCredit = jdbc.sql("""
					update service_credit set state='REVOKED', appointment_id=null, updated_at=:now, version=version+1
					 where id=:id and state='HELD' and appointment_id=:appointmentId
					""").param("now", database(now)).param("id", appointment.creditId())
					.param("appointmentId", appointment.id()).update();
			if (revokedCredit != 1) throw consistencyConflict();
			revocationLedger(appointment.creditId(), actorId, appointment.id(), now);
			jdbc.sql("""
					insert into appointment_status_history
					 (id, appointment_id, from_status, to_status, changed_by, reason, changed_at, credit_outcome)
					values (:id, :appointmentId, :fromStatus, 'CANCELLED', :actorId, :reason, :now, 'REVOKED')
					""").param("id", UUID.randomUUID()).param("appointmentId", appointment.id())
					.param("fromStatus", appointment.status()).param("actorId", actorId).param("reason", REASON)
					.param("now", database(now)).update();
			outbox.record(appointment.id(), UUID.randomUUID(), now);
		}
		return appointments.size();
	}

	private int revokeUnusedCredits(UUID actorId, UUID subscriptionId, Instant now) {
		var credits = jdbc.sql("""
				select c.id from service_credit c join service_credit_period p on p.id=c.period_id
				 where p.account_id=:actorId and p.source_reference=:source and c.state in ('AVAILABLE','UPGRADE_HELD')
				 for update of c
				""").param("actorId", actorId).param("source", subscriptionId.toString()).query(UUID.class).list();
		for (var creditId : credits) {
			var revoked = jdbc.sql("""
					update service_credit set state='REVOKED', appointment_id=null, updated_at=:now, version=version+1
					 where id=:id and state in ('AVAILABLE','UPGRADE_HELD')
					""").param("now", database(now)).param("id", creditId).update();
			if (revoked != 1) throw consistencyConflict();
			revocationLedger(creditId, actorId, null, now);
		}
		return credits.size();
	}

	private void revocationLedger(UUID creditId, UUID actorId, UUID appointmentId, Instant now) {
		jdbc.sql("""
				insert into service_credit_ledger
				 (id, credit_id, account_id, event_type, appointment_id, idempotency_key, occurred_at)
				values (:id, :creditId, :actorId, 'REVOKED', null, :key, :now)
				""").param("id", UUID.randomUUID()).param("creditId", creditId).param("actorId", actorId)
				.param("key", "subscription-cancel:" + creditId).param("now", database(now)).update();
	}

	private void failPendingUpgrade(UUID subscriptionId, Instant now) {
		jdbc.sql("""
				update payment_transaction set status='FAILED', terminal_reason='CANCELLED', failed_at=:now,
				 updated_at=:now, version=version+1 where subscription_id=:subscriptionId
				 and purpose='UPGRADE' and status='PENDING'
				""").param("now", database(now)).param("subscriptionId", subscriptionId).update();
		jdbc.sql("""
				update subscription_upgrade set status='FAILED', updated_at=:now, version=version+1
				 where subscription_id=:subscriptionId and status='PENDING_PAYMENT'
				""").param("now", database(now)).param("subscriptionId", subscriptionId).update();
	}

	private void disablePaidEntitlement(UUID actorId, UUID subscriptionId, Instant now) {
		jdbc.sql("""
				delete from current_service_entitlement
				 where account_id=:actorId and source='PAID' and source_reference=:source
				""").param("actorId", actorId)
				.param("source", subscriptionId.toString()).update();
	}

	private List<Appointment> liveAppointments(UUID actorId, Instant now) {
		return jdbc.sql("""
				select id, service_credit_id, status, scheduled_end_at from appointment
				 where user_account_id=:actorId and scheduled_start_at<=:now and scheduled_end_at>:now
				 and status in ('CONFIRMED','IN_PROGRESS') order by scheduled_start_at, id for update
				""").param("actorId", actorId).param("now", database(now))
				.query((row, ignored) -> new Appointment(row.getObject("id", UUID.class),
						row.getObject("service_credit_id", UUID.class), row.getString("status"),
						row.getTimestamp("scheduled_end_at").toInstant())).list();
	}

	private Subscription subscription(UUID subscriptionId, boolean lock) {
		return jdbc.sql("""
				select s.id, s.user_id, v.plan_code, s.status, s.current_period_start, s.current_period_end,
				 s.cancellation_requested_at, s.cancellation_effective_at, s.cancellation_live_appointment_id,
				 a.scheduled_end_at from user_subscription s join subscription_plan_version v on v.id=s.plan_version_id
				 left join appointment a on a.id=s.cancellation_live_appointment_id where s.id=:id
				""" + (lock ? " for update of s" : "")).param("id", subscriptionId)
				.query((row, ignored) -> new Subscription(row.getObject("id", UUID.class),
						row.getObject("user_id", UUID.class), row.getString("plan_code"), row.getString("status"),
						instant(row, "current_period_start"), instant(row, "current_period_end"),
						instant(row, "cancellation_requested_at"), instant(row, "cancellation_effective_at"),
						row.getObject("cancellation_live_appointment_id", UUID.class), instant(row, "scheduled_end_at")))
				.optional().orElse(null);
	}

	private Subscription owned(UUID actorId, UUID subscriptionId, boolean lock) {
		var subscription = subscription(subscriptionId, lock);
		if (subscription == null || !subscription.userId().equals(actorId)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND", "Subscription was not found");
		}
		return subscription;
	}

	private Command command(UUID actorId, String key) {
		return jdbc.sql("""
				select subscription_id, request_fingerprint, cancelled_appointment_count, revoked_credit_count
				 from subscription_cancellation_command where actor_account_id=:actorId and idempotency_key=:key
				""").param("actorId", actorId).param("key", key).query((row, ignored) -> new Command(
				row.getObject("subscription_id", UUID.class), row.getString("request_fingerprint"),
				row.getInt("cancelled_appointment_count"), row.getInt("revoked_credit_count"))).optional().orElse(null);
	}

	private SubscriptionCancellationResponse response(Subscription subscription, int cancelled, int revoked) {
		return new SubscriptionCancellationResponse(subscription.id(), subscription.planCode(), subscription.status(),
				subscription.periodEnd(), subscription.cancellationRequestedAt(), subscription.cancellationEffectiveAt(),
				subscription.liveAppointmentId(), subscription.liveSessionEndsAt(), cancelled, revoked, clock.instant());
	}

	private void lock(String key) {
		jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", key)
				.query((row, ignored) -> {
					row.getObject(1);
					return Boolean.TRUE;
				}).single();
	}

	private void validateKey(String key) {
		if (key == null || key.length() < 16 || key.length() > 128) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
					"Idempotency-Key must contain between 16 and 128 characters");
		}
	}

	private String fingerprint(UUID subscriptionId) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(subscriptionId.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private ApiException conflict(String code, String detail) {
		return new ApiException(HttpStatus.CONFLICT, code, detail);
	}

	private ApiException consistencyConflict() {
		return conflict("SUBSCRIPTION_CANCELLATION_CONFLICT",
				"Subscription cancellation could not be applied safely");
	}

	private OffsetDateTime database(Instant value) {
		return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
	}

	private Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private record Appointment(UUID id, UUID creditId, String status, Instant scheduledEndAt) { }
	private record Command(UUID subscriptionId, String fingerprint, int cancelledAppointments, int revokedCredits) { }
	private record Subscription(UUID id, UUID userId, String planCode, String status, Instant periodStart,
			Instant periodEnd, Instant cancellationRequestedAt, Instant cancellationEffectiveAt,
			UUID liveAppointmentId, Instant liveSessionEndsAt) { }
}

package com.mentalbridge.consultation.specialist;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpecialistAccountLifecycleService {

	private static final Logger LOGGER = LoggerFactory.getLogger(SpecialistAccountLifecycleService.class);
	public static final UUID SYSTEM_ACTOR_ID = new UUID(0, 0);

	private final SpecialistProfileRepository profiles;
	private final SpecialistProfileStatusHistoryRepository history;
	private final SpecialistSuspensionEffects suspensionEffects;
	private final JdbcClient jdbc;

	public SpecialistAccountLifecycleService(SpecialistProfileRepository profiles,
			SpecialistProfileStatusHistoryRepository history,
			SpecialistSuspensionEffects suspensionEffects,
			JdbcClient jdbc) {
		this.profiles = profiles;
		this.history = history;
		this.suspensionEffects = suspensionEffects;
		this.jdbc = jdbc;
	}

	@Transactional
	public void handleAccountStateChanged(UUID accountId, String status, String role, String reasonCode,
			Instant occurredAt) {
		var timestamp = occurredAt != null ? occurredAt : Instant.now();
		if ("SPECIALIST".equalsIgnoreCase(role)) {
			handleSpecialistStateChanged(accountId, status, reasonCode, timestamp);
		}
		else if ("USER".equalsIgnoreCase(role)) {
			handleUserStateChanged(accountId, status, reasonCode, timestamp);
		}
	}

	private void handleSpecialistStateChanged(UUID specialistId, String status, String reasonCode, Instant occurredAt) {
		var profileOpt = profiles.findByIdForUpdate(specialistId);
		if (profileOpt.isEmpty()) {
			LOGGER.info("No specialist profile found for accountId={}, skipping lifecycle transition", specialistId);
			return;
		}
		var profile = profileOpt.get();

		if ("DISABLED".equalsIgnoreCase(status)) {
			if (profile.approvalStatus() == SpecialistApprovalStatus.APPROVED) {
				var decisionReason = mapSuspensionReason(reasonCode);
				suspensionEffects.apply(specialistId, SYSTEM_ACTOR_ID, occurredAt);
				profile.suspend(SYSTEM_ACTOR_ID, decisionReason, occurredAt);
				profiles.saveAndFlush(profile);
				history.saveAndFlush(new SpecialistProfileStatusHistoryEntity(specialistId,
						SpecialistApprovalStatus.SUSPENDED, SYSTEM_ACTOR_ID,
						SpecialistProfileStatusHistoryEntity.ActorRole.ADMIN, decisionReason, occurredAt));
				LOGGER.info("Specialist profile suspended via lifecycle fact: accountId={}", specialistId);
			}
			else if (profile.approvalStatus() == SpecialistApprovalStatus.SUSPENDED) {
				LOGGER.debug("Specialist profile already SUSPENDED: accountId={}", specialistId);
			}
		}
		else if ("ACTIVE".equalsIgnoreCase(status)) {
			if (profile.approvalStatus() == SpecialistApprovalStatus.SUSPENDED) {
				profile.restore(SYSTEM_ACTOR_ID, occurredAt);
				profiles.saveAndFlush(profile);
				history.saveAndFlush(new SpecialistProfileStatusHistoryEntity(specialistId,
						SpecialistApprovalStatus.APPROVED, SYSTEM_ACTOR_ID,
						SpecialistProfileStatusHistoryEntity.ActorRole.ADMIN, occurredAt));
				LOGGER.info("Specialist profile restored via lifecycle fact: accountId={}", specialistId);
			}
			else if (profile.approvalStatus() == SpecialistApprovalStatus.APPROVED) {
				LOGGER.debug("Specialist profile already APPROVED: accountId={}", specialistId);
			}
		}
	}

	private void handleUserStateChanged(UUID userId, String status, String reasonCode, Instant occurredAt) {
		if ("DISABLED".equalsIgnoreCase(status)) {
			cancelUserUpcomingAppointmentsAndReleaseCredits(userId, occurredAt);
		}
	}

	private void cancelUserUpcomingAppointmentsAndReleaseCredits(UUID userId, Instant now) {
		var appointments = jdbc.sql("""
				select id, service_credit_id, status from appointment
				where user_account_id=:userId and scheduled_start_at > :now
				and status in ('REQUESTED', 'CONFIRMED')
				order by scheduled_start_at, id for update
				""").param("userId", userId).param("now", databaseInstant(now))
				.query((row, ignored) -> new UserAppointmentHold(row.getObject("id", UUID.class),
						row.getObject("service_credit_id", UUID.class), row.getString("status")))
				.list();

		for (var appt : appointments) {
			cancelAndReleaseUserAppointment(appt, userId, now);
		}
	}

	private void cancelAndReleaseUserAppointment(UserAppointmentHold appt, UUID userId, Instant now) {
		var cancelled = jdbc.sql("""
				update appointment set status='CANCELLED', cancellation_reason='USER_CANCELLED',
				cancelled_at=:now, cancelled_by=:userId, cancellation_credit_outcome='RELEASED',
				updated_at=:now, version=version+1
				where id=:appointmentId and status in ('REQUESTED', 'CONFIRMED')
				""").param("now", databaseInstant(now)).param("userId", userId)
				.param("appointmentId", appt.id()).update();
		if (cancelled != 1) return;

		var released = jdbc.sql("""
				update service_credit credit set state='AVAILABLE', appointment_id=null,
				updated_at=:now, version=version+1
				where credit.id=:creditId and credit.state='HELD'
				and credit.appointment_id=:appointmentId
				and exists (
				    select 1 from service_credit_period period
				    where period.id=credit.period_id and period.account_id=:userId
				)
				""").param("now", databaseInstant(now)).param("creditId", appt.creditId())
				.param("appointmentId", appt.id()).param("userId", userId).update();
		if (released != 1) return;

		jdbc.sql("""
				insert into service_credit_ledger (
				    id, credit_id, account_id, event_type, appointment_id, idempotency_key, occurred_at
				) values (:id, :creditId, :accountId, 'RELEASED', :appointmentId, :key, :now)
				""").param("id", UUID.randomUUID()).param("creditId", appt.creditId())
				.param("accountId", userId).param("appointmentId", appt.id())
				.param("key", "user-suspension:" + appt.id()).param("now", databaseInstant(now)).update();

		jdbc.sql("""
				insert into appointment_status_history (
				    id, appointment_id, from_status, to_status, changed_by, reason, changed_at, credit_outcome
				) values (:id, :appointmentId, :fromStatus, 'CANCELLED', :changedBy, 'USER_CANCELLED', :now, 'RELEASED')
				""").param("id", UUID.randomUUID()).param("appointmentId", appt.id())
				.param("fromStatus", appt.status()).param("changedBy", SYSTEM_ACTOR_ID)
				.param("now", databaseInstant(now)).update();
	}

	private SpecialistDecisionReasonCode mapSuspensionReason(String reasonCode) {
		if (reasonCode == null) return SpecialistDecisionReasonCode.ACCOUNT_REVIEW_REQUIRED;
		return switch (reasonCode) {
			case "POLICY_VIOLATION" -> SpecialistDecisionReasonCode.POLICY_VIOLATION;
			case "ACCOUNT_REVIEW_REQUIRED" -> SpecialistDecisionReasonCode.ACCOUNT_REVIEW_REQUIRED;
			case "SAFETY_CONCERN" -> SpecialistDecisionReasonCode.POLICY_VIOLATION;
			default -> SpecialistDecisionReasonCode.ACCOUNT_REVIEW_REQUIRED;
		};
	}

	private OffsetDateTime databaseInstant(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private record UserAppointmentHold(UUID id, UUID creditId, String status) {
	}
}

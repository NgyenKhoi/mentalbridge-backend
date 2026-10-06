package com.mentalbridge.consultation.earnings;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class SpecialistEarningService {

	static final String POLICY_VERSION = "specialist-earning-v1";
	static final String CURRENCY = "VND";
	static final int SPECIALIST_SHARE_BPS = 7_000;

	private final JdbcClient jdbc;
	private final PayoutProperties properties;
	private final Clock clock;

	public SpecialistEarningService(JdbcClient jdbc, PayoutProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.clock = clock;
	}

	public void createForCompletedAppointment(UUID appointmentId) {
		var source = jdbc.sql("""
				select a.id, a.completion_fact_id, a.service_credit_id, a.specialist_account_id,
				       a.session_settled_at, p.plan_version, p.credit_allocation_minor
				from appointment a
				join service_credit c on c.id=a.service_credit_id
				join service_credit_period p on p.id=c.period_id
				where a.id=:appointmentId and a.status='COMPLETED' and a.session_outcome='COMPLETED'
				  and a.completion_fact_id is not null and c.state='CONSUMED'
				  and c.appointment_id=a.id
				for update of a, c
				""").param("appointmentId", appointmentId).query((row, ignored) -> new Source(
				row.getObject("id", UUID.class), row.getObject("completion_fact_id", UUID.class),
				row.getObject("service_credit_id", UUID.class), row.getObject("specialist_account_id", UUID.class),
				row.getTimestamp("session_settled_at").toInstant(), row.getString("plan_version"),
				row.getLong("credit_allocation_minor"))).optional();
		if (source.isEmpty()) return;
		var value = source.orElseThrow();
		var availableAt = value.earnedAt().plus(properties.getSettlementHold());
		var now = clock.instant();
		var specialistAmount = Math.multiplyExact(value.creditAllocationVnd(), SPECIALIST_SHARE_BPS) / 10_000;
		var platformAmount = value.creditAllocationVnd() - specialistAmount;
		jdbc.sql("""
				insert into specialist_earning (
				 id, appointment_id, completion_fact_id, consumed_credit_id, specialist_account_id,
				 plan_version, currency, credit_allocation_minor, specialist_share_bps,
				 specialist_amount_minor, platform_allocation_minor, idempotency_source,
				 status, earned_at, settlement_available_at, created_at, updated_at
				) values (
				 :id, :appointmentId, :completionFactId, :creditId, :specialistId,
				 :planVersion, :currency, :allocation, :share,
				 :specialistAmount, :platformAmount, :source,
				 'PENDING_SETTLEMENT', :earnedAt, :availableAt, :now, :now
				) on conflict (appointment_id) do nothing
				""").param("id", UUID.randomUUID()).param("appointmentId", value.appointmentId())
				.param("completionFactId", value.completionFactId()).param("creditId", value.creditId())
				.param("specialistId", value.specialistId()).param("planVersion", value.planVersion())
				.param("currency", CURRENCY).param("allocation", value.creditAllocationVnd())
				.param("share", SPECIALIST_SHARE_BPS).param("specialistAmount", specialistAmount)
				.param("platformAmount", platformAmount)
				.param("source", "completion:" + value.completionFactId())
				.param("earnedAt", databaseInstant(value.earnedAt())).param("availableAt", databaseInstant(availableAt))
				.param("now", databaseInstant(now)).update();
	}

	private OffsetDateTime databaseInstant(java.time.Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private record Source(UUID appointmentId, UUID completionFactId, UUID creditId, UUID specialistId,
			java.time.Instant earnedAt, String planVersion, long creditAllocationVnd) {
	}
}

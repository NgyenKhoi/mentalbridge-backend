package com.mentalbridge.consultation.credits;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.entitlement.CurrentServiceEntitlementService;
import com.mentalbridge.consultation.entitlement.EntitlementSource;
import com.mentalbridge.consultation.entitlement.ServicePackage;
import com.mentalbridge.consultation.shared.ApiException;

@Service
public class ServiceCreditService {

	static final String POLICY_VERSION = "consultation-credit-v1";
	private static final int HISTORY_LIMIT = 100;

	private final CurrentServiceEntitlementService entitlements;
	private final JdbcClient jdbc;
	private final Clock clock;

	public ServiceCreditService(CurrentServiceEntitlementService entitlements, JdbcClient jdbc, Clock clock) {
		this.entitlements = entitlements;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional
	public ServiceCreditResponse current(UUID accountId) {
		var entitlement = entitlements.current(accountId);
		CreditPeriod period = null;
		if (entitlement.packageCode() != ServicePackage.FREE) {
			period = ensurePeriod(entitlement);
		}
		return response(accountId, entitlement.packageCode(), entitlement.source(), entitlement.sourceReference(),
				entitlement.effectiveFrom(), entitlement.effectiveUntil(), period);
	}

	@Transactional
	public void transition(UUID accountId, UUID creditId, UUID appointmentId, CreditEventType eventType,
			String idempotencyKey) {
		if (eventType == CreditEventType.PROVISIONED || idempotencyKey == null
				|| idempotencyKey.length() < 16 || idempotencyKey.length() > 128) {
			throw new IllegalArgumentException("A valid transition and idempotency key are required");
		}
		var replay = jdbc.sql("""
				select credit_id, event_type, appointment_id from service_credit_ledger
				where account_id=:accountId and idempotency_key=:key
				""").param("accountId", accountId).param("key", idempotencyKey)
				.query((row, ignored) -> new ExistingCommand(row.getObject("credit_id", UUID.class),
						CreditEventType.valueOf(row.getString("event_type")), row.getObject("appointment_id", UUID.class)))
				.optional();
		if (replay.isPresent()) {
			var command = replay.orElseThrow();
			if (command.creditId().equals(creditId) && command.eventType() == eventType
					&& command.appointmentId().equals(appointmentId)) return;
			throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key was reused for another credit transition");
		}
		var commandTime = clock.instant();
		var credit = jdbc.sql("""
				select c.state, c.appointment_id, p.period_end from service_credit c
				join service_credit_period p on p.id=c.period_id
				where c.id=:creditId and p.account_id=:accountId for update
				""").param("creditId", creditId).param("accountId", accountId)
				.query((row, ignored) -> new CreditState(row.getString("state"),
						row.getObject("appointment_id", UUID.class), row.getTimestamp("period_end").toInstant())).optional()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SERVICE_CREDIT_NOT_FOUND",
						"Service credit was not found"));
		if (!credit.periodEnd().isAfter(commandTime)) {
			throw conflict("SERVICE_CREDIT_PERIOD_EXPIRED", "Service credit period has ended");
		}
		var nextState = nextState(credit, appointmentId, eventType);
		var nextAppointment = nextState.equals("AVAILABLE") ? null : appointmentId;
		jdbc.sql("""
				update service_credit set state=:state, appointment_id=:appointmentId,
				updated_at=:now, version=version+1 where id=:creditId
				""").param("state", nextState).param("appointmentId", nextAppointment)
				.param("now", databaseInstant(commandTime))
				.param("creditId", creditId).update();
		jdbc.sql("""
				insert into service_credit_ledger (
				    id, credit_id, account_id, event_type, appointment_id, idempotency_key, occurred_at
				) values (:id, :creditId, :accountId, :eventType, :appointmentId, :key, :now)
				""").param("id", UUID.randomUUID()).param("creditId", creditId).param("accountId", accountId)
				.param("eventType", eventType.name()).param("appointmentId", appointmentId).param("key", idempotencyKey)
				.param("now", databaseInstant(commandTime)).update();
	}

	private String nextState(CreditState credit, UUID appointmentId, CreditEventType eventType) {
		return switch (eventType) {
			case HELD -> {
				if (!credit.state().equals("AVAILABLE")) throw invalidTransition();
				yield "HELD";
			}
			case RELEASED -> {
				assertHeldBy(credit, appointmentId);
				yield "AVAILABLE";
			}
			case CONSUMED -> {
				assertHeldBy(credit, appointmentId);
				yield "CONSUMED";
			}
			case FORFEITED -> {
				assertHeldBy(credit, appointmentId);
				yield "FORFEITED";
			}
			case PROVISIONED -> throw new IllegalArgumentException("Provisioning is automatic");
		};
	}

	private void assertHeldBy(CreditState credit, UUID appointmentId) {
		if (!credit.state().equals("HELD") || !appointmentId.equals(credit.appointmentId())) throw invalidTransition();
	}

	private ApiException invalidTransition() {
		return conflict("SERVICE_CREDIT_TRANSITION_CONFLICT", "Service credit cannot make that transition");
	}

	private CreditPeriod ensurePeriod(CurrentServiceEntitlementService.EntitlementDecision entitlement) {
		var periodId = UUID.randomUUID();
		jdbc.sql("""
				insert into service_credit_period (
				    id, account_id, plan_version, package_code, source, source_reference,
				    period_start, period_end, allocated_count, created_at, updated_at
				) values (
				    :id, :accountId, :planVersion, :packageCode, :source, :sourceReference,
				    :periodStart, :periodEnd, :allocatedCount, :now, :now
				) on conflict (account_id, plan_version, period_start, period_end) do nothing
				""").param("id", periodId).param("accountId", entitlement.accountId())
				.param("planVersion", entitlement.policyVersion()).param("packageCode", entitlement.packageCode().name())
				.param("source", entitlement.source().name()).param("sourceReference", entitlement.sourceReference())
				.param("periodStart", databaseInstant(entitlement.effectiveFrom()))
				.param("periodEnd", databaseInstant(entitlement.effectiveUntil()))
				.param("allocatedCount", allocation(entitlement.packageCode())).param("now", databaseNow()).update();

		var period = findPeriod(entitlement);
		if (period.source() != entitlement.source() || !period.sourceReference().equals(entitlement.sourceReference())) {
			throw conflict("CREDIT_PERIOD_PROVENANCE_CONFLICT",
					"The entitlement period already has different credit provenance");
		}
		var target = allocation(entitlement.packageCode());
		if (period.allocatedCount() > target) {
			throw conflict("SUBSCRIPTION_DOWNGRADE_NOT_SUPPORTED", "Credit entitlement cannot be downgraded in place");
		}
		if (period.allocatedCount() < target) {
			jdbc.sql("""
					update service_credit_period
					set package_code=:packageCode, allocated_count=:allocatedCount, updated_at=:now, version=version+1
					where id=:id and allocated_count=:previous
					""").param("packageCode", entitlement.packageCode().name()).param("allocatedCount", target)
					.param("now", databaseNow()).param("id", period.id()).param("previous", period.allocatedCount()).update();
			period = findPeriod(entitlement);
		}
		for (int ordinal = 1; ordinal <= target; ordinal++) provision(period, ordinal);
		return period;
	}

	private CreditPeriod findPeriod(CurrentServiceEntitlementService.EntitlementDecision entitlement) {
		return jdbc.sql("""
				select id, package_code, source, source_reference, allocated_count
				from service_credit_period
				where account_id=:accountId and plan_version=:planVersion
				  and period_start=:periodStart and period_end=:periodEnd
				for update
				""").param("accountId", entitlement.accountId()).param("planVersion", entitlement.policyVersion())
				.param("periodStart", databaseInstant(entitlement.effectiveFrom()))
				.param("periodEnd", databaseInstant(entitlement.effectiveUntil()))
				.query((row, ignored) -> new CreditPeriod(row.getObject("id", UUID.class),
						ServicePackage.valueOf(row.getString("package_code")),
						EntitlementSource.valueOf(row.getString("source")), row.getString("source_reference"),
						row.getInt("allocated_count"))).single();
	}

	private void provision(CreditPeriod period, int ordinal) {
		var creditId = UUID.randomUUID();
		var inserted = jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, created_at, updated_at)
				values (:id, :periodId, :ordinal, 'AVAILABLE', :now, :now)
				on conflict (period_id, ordinal) do nothing returning id
				""").param("id", creditId).param("periodId", period.id()).param("ordinal", ordinal)
				.param("now", databaseNow()).query(UUID.class).optional();
		inserted.ifPresent(id -> jdbc.sql("""
				insert into service_credit_ledger (
				    id, credit_id, account_id, event_type, appointment_id, idempotency_key, occurred_at
				) select :id, :creditId, account_id, 'PROVISIONED', null, :key, :now
				  from service_credit_period where id=:periodId
				""").param("id", UUID.randomUUID()).param("creditId", id).param("key", "provision:" + period.id() + ":" + ordinal)
					.param("now", databaseNow()).param("periodId", period.id()).update());
	}

	private ServiceCreditResponse response(UUID accountId, ServicePackage packageCode, EntitlementSource source,
			String sourceReference, Instant periodStart, Instant periodEnd, CreditPeriod period) {
		var counts = period == null ? Map.<String, Integer>of() : jdbc.sql("""
				select state, count(*)::integer as count from service_credit
				where period_id=:periodId group by state
				""").param("periodId", period.id()).query((row, ignored) -> Map.entry(row.getString("state"), row.getInt("count")))
				.list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		var released = jdbc.sql("""
				select count(*)::integer from service_credit_ledger
				where account_id=:accountId and event_type='RELEASED'
				""").param("accountId", accountId).query(Integer.class).single();
		var history = history(accountId);
		var balance = new ServiceCreditResponse.Balance(counts.getOrDefault("AVAILABLE", 0),
				counts.getOrDefault("HELD", 0), counts.getOrDefault("CONSUMED", 0),
				counts.getOrDefault("FORFEITED", 0), period == null ? 0 : period.allocatedCount(), released);
		return new ServiceCreditResponse(accountId, packageCode, source, sourceReference, periodStart, periodEnd,
				POLICY_VERSION, balance, history, clock.instant());
	}

	private List<ServiceCreditResponse.LedgerEvent> history(UUID accountId) {
		return jdbc.sql("""
				select l.id, l.credit_id, l.event_type, p.source, p.package_code, l.appointment_id, l.occurred_at
				from service_credit_ledger l
				join service_credit c on c.id=l.credit_id
				join service_credit_period p on p.id=c.period_id
				where l.account_id=:accountId
				order by l.occurred_at desc, l.id desc limit :limit
				""").param("accountId", accountId).param("limit", HISTORY_LIMIT)
				.query((row, ignored) -> new ServiceCreditResponse.LedgerEvent(row.getObject("id", UUID.class),
						row.getObject("credit_id", UUID.class), CreditEventType.valueOf(row.getString("event_type")),
						EntitlementSource.valueOf(row.getString("source")),
						ServicePackage.valueOf(row.getString("package_code")),
						row.getObject("appointment_id", UUID.class), row.getTimestamp("occurred_at").toInstant())).list();
	}

	private int allocation(ServicePackage packageCode) {
		return switch (packageCode) {
			case FREE -> 0;
			case PLUS -> 1;
			case PREMIUM -> 3;
		};
	}

	private ApiException conflict(String code, String message) {
		return new ApiException(HttpStatus.CONFLICT, code, message);
	}

	private OffsetDateTime databaseNow() {
		return databaseInstant(clock.instant());
	}

	private OffsetDateTime databaseInstant(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private record CreditPeriod(UUID id, ServicePackage packageCode, EntitlementSource source,
			String sourceReference, int allocatedCount) {
	}

	private record CreditState(String state, UUID appointmentId, Instant periodEnd) {
	}

	private record ExistingCommand(UUID creditId, CreditEventType eventType, UUID appointmentId) {
	}
}

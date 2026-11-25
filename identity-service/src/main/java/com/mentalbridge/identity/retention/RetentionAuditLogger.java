package com.mentalbridge.identity.retention;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.identity.account.SecurityAuditEventEntity;
import com.mentalbridge.identity.account.SecurityAuditEventRepository;

@Component
public class RetentionAuditLogger {

	private final SecurityAuditEventRepository auditEvents;

	public RetentionAuditLogger(SecurityAuditEventRepository auditEvents) {
		this.auditEvents = auditEvents;
	}

	/**
	 * Records a DENIED/FAILED audit event in an isolated new transaction (REQUIRES_NEW).
	 * This ensures that denied audit records persist and survive transaction rollback
	 * caused by subsequent exceptions (active hold, retry exhaustion, schedule in future, etc.).
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordDenied(SecurityAuditEventEntity event) {
		auditEvents.save(event);
	}

	/**
	 * Records a SUCCEEDED audit event within the current business transaction (REQUIRED).
	 * If the surrounding business operation rolls back, this audit event rolls back with it,
	 * preventing false success audit evidence.
	 */
	@Transactional(propagation = Propagation.REQUIRED)
	public void recordSuccess(SecurityAuditEventEntity event) {
		auditEvents.save(event);
	}

	/**
	 * Dispatches recording based on outcome: DENIED/FAILED uses REQUIRES_NEW,
	 * while SUCCEEDED uses REQUIRED.
	 */
	public void record(SecurityAuditEventEntity event) {
		if (event != null && ("DENIED".equalsIgnoreCase(event.getOutcome()) || "FAILED".equalsIgnoreCase(event.getOutcome()))) {
			recordDenied(event);
		} else {
			recordSuccess(event);
		}
	}
}

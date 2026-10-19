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

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(SecurityAuditEventEntity event) {
		auditEvents.save(event);
	}
}


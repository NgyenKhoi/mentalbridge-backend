package com.mentalbridge.care.supportplan;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;

@RestController
@RequestMapping("/api/v2/support-plan-occurrences")
public class JournalEntryReconciliationController {

	private final JournalEntryReconciliationService reconciliation;

	public JournalEntryReconciliationController(JournalEntryReconciliationService reconciliation) {
		this.reconciliation = reconciliation;
	}

	@PostMapping("/{occurrenceId}/journal/reconcile")
	ResponseEntity<JournalEntryReconciliationWriter.Reconciliation> reconcile(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID occurrenceId, @RequestBody Map<String, UUID> command,
			@RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId) {
		if (command == null || !Set.of("journalId").equals(command.keySet()) || command.get("journalId") == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "An exact Journal entry identifier is required");
		}
		UUID userId;
		try {
			userId = UUID.fromString(jwt.getSubject());
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authenticated account identifier is invalid");
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(reconciliation.reconcile(userId, occurrenceId, command.get("journalId"), jwt.getTokenValue(), correlationId));
	}
}

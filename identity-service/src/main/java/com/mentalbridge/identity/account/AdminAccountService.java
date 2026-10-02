package com.mentalbridge.identity.account;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.identity.authentication.AuthenticationPersistence;
import com.mentalbridge.identity.registration.OutboxEventEntity;
import com.mentalbridge.identity.registration.OutboxEventRepository;

import jakarta.persistence.criteria.Predicate;

@Service
public class AdminAccountService {

	private static final int DEFAULT_LIMIT = 20;
	private static final int MAX_LIMIT = 100;

	private final AccountRepository accounts;
	private final AuthenticationPersistence authentication;
	private final SecurityAuditEventRepository auditEvents;
	private final OutboxEventRepository outboxEvents;
	private final Clock clock;

	public AdminAccountService(AccountRepository accounts, AuthenticationPersistence authentication,
			SecurityAuditEventRepository auditEvents, OutboxEventRepository outboxEvents, Clock clock) {
		this.accounts = accounts;
		this.authentication = authentication;
		this.auditEvents = auditEvents;
		this.outboxEvents = outboxEvents;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public AccountPage searchAccounts(AccountStatus status, RoleCode role, String email, String cursor, Integer requestedLimit) {
		int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new InvalidAdminAccountQueryException("Limit must be between 1 and 100");
		}
		if (cursor != null && cursor.isBlank()) throw new InvalidAdminAccountQueryException("Cursor is invalid");
		if (email != null && email.isBlank()) throw new InvalidAdminAccountQueryException("Email filter is invalid");
		Cursor after = cursor == null ? null : decodeCursor(cursor);
		String normalizedEmail = email == null ? null : email.strip().toLowerCase(Locale.ROOT);

		Specification<AccountEntity> specification = (root, query, builder) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(builder.notEqual(root.get("status"), AccountStatus.DELETED));
			if (status != null) predicates.add(builder.equal(root.get("status"), status));
			if (role != null) predicates.add(builder.equal(root.get("role"), role));
			if (normalizedEmail != null) predicates.add(builder.equal(root.get("email"), normalizedEmail));
			if (after != null) {
				predicates.add(builder.or(
						builder.lessThan(root.<Instant>get("createdAt"), after.createdAt()),
						builder.and(builder.equal(root.<Instant>get("createdAt"), after.createdAt()),
								builder.lessThan(root.<UUID>get("id"), after.accountId()))));
			}
			return builder.and(predicates.toArray(Predicate[]::new));
		};

		var pageable = PageRequest.of(0, limit + 1, Sort.by("createdAt").descending().and(Sort.by("id").descending()));
		var found = accounts.findAll(specification, pageable).getContent();
		boolean hasNext = found.size() > limit;
		var pageItems = found.subList(0, Math.min(limit, found.size())).stream().map(this::response).toList();
		String nextCursor = hasNext ? encodeCursor(found.get(limit - 1)) : null;
		return new AccountPage(pageItems, nextCursor);
	}

	@Transactional(readOnly = true)
	public AccountController.AccountResponse getAccountDetail(UUID accountId) {
		return accounts.findById(accountId).filter(account -> account.status() != AccountStatus.DELETED)
				.map(this::response).orElseThrow(() -> new AccountNotFoundException(accountId));
	}

	@Transactional(noRollbackFor = DedicatedAdminProtectionException.class)
	public AccountController.AccountResponse changeAccountState(UUID actorId, UUID accountId,
			AccountStateChangeRequest request, long expectedVersion, UUID correlationId) {
		var account = accounts.findByIdForUpdate(accountId)
				.filter(candidate -> candidate.status() != AccountStatus.DELETED)
				.orElseThrow(() -> new AccountNotFoundException(accountId));
		if (account.role() == RoleCode.ADMIN) {
			Instant now = clock.instant();
			String action = request != null && request.status() == AccountStatus.ACTIVE
					? "ACCOUNT_RESTORED"
					: "ACCOUNT_DISABLED";
			String reason = request != null && request.reasonCode() != null
					? request.reasonCode().name()
					: "DEDICATED_ADMIN_PROTECTED";
			auditEvents.save(new SecurityAuditEventEntity(UUID.randomUUID(), account.id(), actorId, action, "DENIED",
					reason, correlationId, null, now, now));
			throw new DedicatedAdminProtectionException(accountId);
		}
		if (account.version() != expectedVersion) {
			throw new AccountVersionMismatchException(accountId, expectedVersion, account.version());
		}

		AccountStatus requestedStatus = request.status();
		validateReason(requestedStatus, request.reasonCode());
		if (requestedStatus == AccountStatus.DISABLED && account.status() == AccountStatus.DISABLED) return response(account);
		if (requestedStatus == AccountStatus.ACTIVE && (account.status() == AccountStatus.ACTIVE
				|| account.status() == AccountStatus.PENDING_EMAIL_VERIFICATION)) return response(account);
		validateTransition(account.status(), requestedStatus);

		Instant now = clock.instant();
		String action;
		if (requestedStatus == AccountStatus.DISABLED) {
			account.disable(now);
			authentication.revokeAll(account.id(), "ACCOUNT_DISABLED", now);
			action = "ACCOUNT_DISABLED";
		}
		else {
			account.restore(now);
			action = "ACCOUNT_RESTORED";
		}
		accounts.saveAndFlush(account);
		auditEvents.save(new SecurityAuditEventEntity(UUID.randomUUID(), account.id(), actorId, action, "SUCCEEDED",
				request.reasonCode().name(), correlationId, null, now, now));
		outboxEvents.save(new OutboxEventEntity("identity.account.state-changed", account.id(), account.version(),
				correlationId, Map.of("accountId", account.id().toString(), "status", account.status().name(), "role", account.role().name(),
						"reasonCode", request.reasonCode().name()), now));
		return response(account);
	}

	private void validateReason(AccountStatus requested, AccountStateReasonCode reason) {
		if (requested == AccountStatus.DISABLED && reason == AccountStateReasonCode.REVIEW_COMPLETED) {
			throw new InvalidStateTransitionException("REVIEW_COMPLETED is only valid when restoring an account");
		}
		if (requested == AccountStatus.ACTIVE && reason != AccountStateReasonCode.REVIEW_COMPLETED) {
			throw new InvalidStateTransitionException("Account restoration requires REVIEW_COMPLETED");
		}
	}

	private void validateTransition(AccountStatus current, AccountStatus requested) {
		if (requested == AccountStatus.DISABLED) {
			if (current == AccountStatus.ACTIVE || current == AccountStatus.PENDING_EMAIL_VERIFICATION) return;
		}
		if (requested == AccountStatus.ACTIVE) {
			if (current == AccountStatus.DISABLED) return;
		}
		throw new InvalidStateTransitionException("Unsupported account state transition from " + current + " to " + requested);
	}

	private AccountController.AccountResponse response(AccountEntity account) {
		return new AccountController.AccountResponse(account.id(), account.email(), account.status(), List.of(account.role()),
				account.emailVerifiedAt() != null, account.createdAt(), account.updatedAt(), account.version());
	}

	private String encodeCursor(AccountEntity account) {
		String raw = account.createdAt() + "|" + account.id();
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private Cursor decodeCursor(String encoded) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
			String[] parts = raw.split("\\|", -1);
			if (parts.length != 2) throw new IllegalArgumentException();
			return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
		}
		catch (IllegalArgumentException | DateTimeParseException exception) {
			throw new InvalidAdminAccountQueryException("Cursor is invalid");
		}
	}

	public record AccountPage(List<AccountController.AccountResponse> items, String nextCursor) {
		public AccountPage {
			items = List.copyOf(items);
		}
	}

	private record Cursor(Instant createdAt, UUID accountId) { }
}

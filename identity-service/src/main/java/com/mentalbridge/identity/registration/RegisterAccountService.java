package com.mentalbridge.identity.registration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.account.RoleCode;
import com.mentalbridge.identity.idempotency.IdempotencyConflictException;
import com.mentalbridge.identity.idempotency.IdempotencyService;
import com.mentalbridge.identity.security.BCryptPasswordHasher;
import com.mentalbridge.identity.security.PayloadCipher;
import com.mentalbridge.identity.security.SecureTokenService;

@Service
public class RegisterAccountService {

	private static final Duration VERIFICATION_LIFETIME = Duration.ofHours(24);
	private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
	private static final String OPERATION = "REGISTER_ACCOUNT";

	private final RegistrationPersistence persistence;
	private final BCryptPasswordHasher passwordHasher;
	private final SecureTokenService tokens;
	private final ApplicationEventPublisher events;
	private final IdempotencyService idempotency;
	private final PayloadCipher cipher;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public RegisterAccountService(RegistrationPersistence persistence, BCryptPasswordHasher passwordHasher,
			SecureTokenService tokens, ApplicationEventPublisher events, IdempotencyService idempotency, PayloadCipher cipher,
			ObjectMapper objectMapper, Clock clock) {
		this.persistence = persistence;
		this.passwordHasher = passwordHasher;
		this.tokens = tokens;
		this.events = events;
		this.idempotency = idempotency;
		this.cipher = cipher;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Transactional
	public RegistrationPersistence.RegistrationRecord register(String email, String password, RoleCode role,
			String idempotencyKey, UUID correlationId) {
		var now = clock.instant();
		var normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
		var requestHash = cipher.fingerprint(normalizedEmail + "\n" + role.name() + "\n" + password);
		var recordId = UUID.randomUUID();
		if (!idempotency.claim(recordId, OPERATION, idempotencyKey, requestHash,
				now.plus(IDEMPOTENCY_RETENTION), now)) {
			return replay(idempotencyKey, requestHash);
		}
		var challenge = tokens.generate();
		var registered = persistence.create(normalizedEmail, passwordHasher.hash(password), role, tokens.hash(challenge),
				now.plus(VERIFICATION_LIFETIME), correlationId, now);
		idempotency.complete(recordId, registered.accountId(), 201, cipher.encrypt(toJson(registered)),
				cipher.keyVersion(), now);
		events.publishEvent(new RegistrationRequested(registered.accountId(), normalizedEmail, challenge, correlationId));
		return registered;
	}

	private RegistrationPersistence.RegistrationRecord replay(String key, String requestHash) {
		var record = idempotency.findForUpdate(OPERATION, key).orElseThrow(IdempotencyConflictException::new);
		if (!record.requestHash().equals(requestHash) || record.completedAt() == null) {
			throw new IdempotencyConflictException();
		}
		try {
			return objectMapper.readValue(cipher.decrypt(record.responseCiphertext(), record.encryptionKeyVersion()),
					RegistrationReplay.class).toRecord();
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Unable to restore registration outcome", exception);
		}
	}

	private String toJson(RegistrationPersistence.RegistrationRecord registered) {
		try {
			return objectMapper.writeValueAsString(new RegistrationReplay(registered.accountId(),
					registered.status(), registered.createdAt()));
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Unable to store registration outcome", exception);
		}
	}

	private record RegistrationReplay(UUID accountId, AccountStatus status,
			Instant createdAt) {
		RegistrationPersistence.RegistrationRecord toRecord() {
			return new RegistrationPersistence.RegistrationRecord(accountId, status, createdAt);
		}
	}

}

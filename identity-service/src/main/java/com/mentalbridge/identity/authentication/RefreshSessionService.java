package com.mentalbridge.identity.authentication;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.idempotency.IdempotencyConflictException;
import com.mentalbridge.identity.idempotency.IdempotencyService;
import com.mentalbridge.identity.security.PayloadCipher;
import com.mentalbridge.identity.security.SecureTokenService;

@Service
public class RefreshSessionService {
	private static final Duration IDEMPOTENCY_RETENTION = Duration.ofMinutes(15);
	private static final String OPERATION = "REFRESH_SESSION";

	private final AuthenticationPersistence persistence;
	private final SecureTokenService tokens;
	private final JwtTokenService tokenService;
	private final IdempotencyService idempotency;
	private final PayloadCipher cipher;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public RefreshSessionService(AuthenticationPersistence persistence, SecureTokenService tokens,
			JwtTokenService tokenService, IdempotencyService idempotency, PayloadCipher cipher,
			ObjectMapper objectMapper, Clock clock) {
		this.persistence = persistence;
		this.tokens = tokens;
		this.tokenService = tokenService;
		this.idempotency = idempotency;
		this.cipher = cipher;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Transactional(noRollbackFor = InvalidSessionException.class)
	public AuthenticateAccountService.TokenPair refresh(String presentedToken, String idempotencyKey) {
		var now = clock.instant();
		var requestHash = cipher.fingerprint(presentedToken);
		var recordId = UUID.randomUUID();
		if (!idempotency.claim(recordId, OPERATION, idempotencyKey, requestHash,
				now.plus(IDEMPOTENCY_RETENTION), now)) {
			return replay(idempotencyKey, requestHash);
		}
		var current = persistence.findRefreshForUpdate(tokens.hash(presentedToken)).orElse(null);
		if (current == null) {
			completeInvalid(recordId, null, now);
			throw new InvalidSessionException();
		}
		if (current.revokedAt() != null) {
			if ("ROTATED".equals(current.revokeReason())) {
				persistence.revokeFamily(current.familyId(), "REPLAY_DETECTED", now);
			}
			completeInvalid(recordId, current.accountId(), now);
			throw new InvalidSessionException();
		}
		if (current.accountStatus() != AccountStatus.ACTIVE || !current.expiresAt().isAfter(now)) {
			persistence.revokeFamily(current.familyId(), "EXPIRED_OR_INACTIVE", now);
			completeInvalid(recordId, current.accountId(), now);
			throw new InvalidSessionException();
		}

		var refreshToken = tokens.generate();
		var successor = new AuthenticationPersistence.RefreshSession(UUID.randomUUID(), current.familyId(),
				current.accountId(), tokens.hash(refreshToken), null, current.expiresAt(), current.id(), now);
		persistence.rotateRefresh(current.id(), successor, now);
		var accessToken = tokenService.issue(current.accountId(), current.roles(), now);
		var result = new AuthenticateAccountService.TokenPair(accessToken.value(), accessToken.expiresInSeconds(),
				refreshToken, successor.expiresAt());
		idempotency.complete(recordId, current.accountId(), 200, cipher.encrypt(toJson(result)), cipher.keyVersion(), now);
		return result;
	}

	private AuthenticateAccountService.TokenPair replay(String key, String requestHash) {
		var record = idempotency.findForUpdate(OPERATION, key).orElseThrow(IdempotencyConflictException::new);
		if (!record.requestHash().equals(requestHash) || record.completedAt() == null) {
			throw new IdempotencyConflictException();
		}
		if (record.responseStatus() != 200) {
			throw new InvalidSessionException();
		}
		try {
			return objectMapper.readValue(cipher.decrypt(record.responseCiphertext(), record.encryptionKeyVersion()),
					AuthenticateAccountService.TokenPair.class);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Unable to restore refresh outcome", exception);
		}
	}

	private void completeInvalid(UUID recordId, UUID accountId, java.time.Instant now) {
		idempotency.complete(recordId, accountId, 401, cipher.encrypt("{}"), cipher.keyVersion(), now);
	}

	private String toJson(AuthenticateAccountService.TokenPair pair) {
		try {
			return objectMapper.writeValueAsString(pair);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Unable to store refresh outcome", exception);
		}
	}

}

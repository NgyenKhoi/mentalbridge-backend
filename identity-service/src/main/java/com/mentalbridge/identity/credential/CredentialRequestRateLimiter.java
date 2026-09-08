package com.mentalbridge.identity.credential;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.mentalbridge.identity.security.PayloadCipher;

@Repository
public class CredentialRequestRateLimiter {

	private static final Duration COOLDOWN = Duration.ofSeconds(60);
	private static final Duration WINDOW = Duration.ofHours(1);
	private static final int MAXIMUM_REQUESTS = 3;

	private final JdbcClient jdbc;
	private final PayloadCipher fingerprints;

	public CredentialRequestRateLimiter(JdbcClient jdbc, PayloadCipher fingerprints) {
		this.jdbc = jdbc;
		this.fingerprints = fingerprints;
	}

	public void claim(String normalizedEmail, String purpose, Instant now) {
		var subjectKeyHash = fingerprints.fingerprint("credential-request\n" + purpose + "\n" + normalizedEmail);
		var requestedAt = Timestamp.from(now);
		var claimed = jdbc.sql("""
				insert into credential_request_rate_limit (
				  subject_key_hash, purpose, window_started_at, request_count,
				  last_requested_at, created_at, updated_at
				) values (
				  :subjectKeyHash, :purpose, :now, 1, :now, :now, :now
				)
				on conflict (subject_key_hash, purpose) do update set
				  window_started_at = case
				    when credential_request_rate_limit.window_started_at <= :windowCutoff then :now
				    else credential_request_rate_limit.window_started_at
				  end,
				  request_count = case
				    when credential_request_rate_limit.window_started_at <= :windowCutoff then 1
				    else credential_request_rate_limit.request_count + 1
				  end,
				  last_requested_at = :now,
				  updated_at = :now
				where credential_request_rate_limit.last_requested_at <= :cooldownCutoff
				  and (
				    credential_request_rate_limit.window_started_at <= :windowCutoff
				    or credential_request_rate_limit.request_count < :maximumRequests
				  )
				returning 1
				""").param("subjectKeyHash", subjectKeyHash).param("purpose", purpose).param("now", requestedAt)
				.param("windowCutoff", Timestamp.from(now.minus(WINDOW)))
				.param("cooldownCutoff", Timestamp.from(now.minus(COOLDOWN)))
				.param("maximumRequests", MAXIMUM_REQUESTS).query(Integer.class).optional().isPresent();
		if (!claimed) {
			throw new CredentialRateLimitException(retryAfter(subjectKeyHash, purpose, now));
		}
	}

	private long retryAfter(String subjectKeyHash, String purpose, Instant now) {
		var state = jdbc.sql("""
				select window_started_at, request_count, last_requested_at
				from credential_request_rate_limit
				where subject_key_hash = :subjectKeyHash and purpose = :purpose
				""").param("subjectKeyHash", subjectKeyHash).param("purpose", purpose)
				.query((row, metadata) -> new RateState(row.getTimestamp(1).toInstant(), row.getInt(2),
						row.getTimestamp(3).toInstant())).single();
		var allowedAt = state.lastRequestedAt().plus(COOLDOWN);
		if (state.requestCount() >= MAXIMUM_REQUESTS) {
			var windowEnd = state.windowStartedAt().plus(WINDOW);
			if (windowEnd.isAfter(allowedAt)) {
				allowedAt = windowEnd;
			}
		}
		return Math.max(1, Duration.between(now, allowedAt).toSeconds());
	}

	private record RateState(Instant windowStartedAt, int requestCount, Instant lastRequestedAt) {
	}
}

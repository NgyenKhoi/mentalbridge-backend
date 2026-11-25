package com.mentalbridge.care.supportplan;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.mentalbridge.care.configuration.JournalEntryEvidenceProperties;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.JournalEntryEvidenceHttpClient.Evidence;

import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

@Component
public class JournalEntryEvidenceClient {
	private final JournalEntryEvidenceHttpClient http;
	private final Clock clock;
	private final CircuitBreaker circuitBreaker;
	private final Retry retry;

	public JournalEntryEvidenceClient(JournalEntryEvidenceHttpClient http,
			JournalEntryEvidenceProperties properties, Clock clock) {
		this.http = http;
		this.clock = clock;
		this.retry = Retry.of("journalEntryEvidence", RetryConfig.custom()
				.maxAttempts(properties.maxAttempts())
				.intervalFunction(IntervalFunction.ofExponentialRandomBackoff(properties.retryWait(), 2.0, 0.2))
				.retryOnException(JournalEntryEvidenceClient::transientFailure).build());
		this.circuitBreaker = CircuitBreaker.of("journalEntryEvidence", CircuitBreakerConfig.custom()
				.slidingWindowSize(properties.circuitWindowSize())
				.minimumNumberOfCalls(properties.circuitMinimumCalls())
				.failureRateThreshold(properties.circuitFailureRate())
				.waitDurationInOpenState(properties.circuitOpenDuration())
				.permittedNumberOfCallsInHalfOpenState(properties.circuitHalfOpenCalls())
				.recordException(error -> error instanceof InvalidEvidenceException || transientFailure(error))
				.build());
	}

	public Evidence read(UUID userId, UUID journalId, String bearerToken, UUID correlationId) {
		if (userId == null || journalId == null || bearerToken == null || bearerToken.isBlank()
				|| correlationId == null) {
			throw new IllegalArgumentException("Journal evidence context is required");
		}
		Supplier<Evidence> remote = CircuitBreaker.decorateSupplier(circuitBreaker,
				() -> validate(http.read("Bearer " + bearerToken, correlationId.toString(), journalId),
						userId, journalId));
		try {
			return Retry.decorateSupplier(retry, remote).get();
		}
		catch (RuntimeException exception) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "JOURNAL_ENTRY_EVIDENCE_UNAVAILABLE",
					"Journal entry evidence is temporarily unavailable; no completion was recorded");
		}
	}

	CircuitBreaker.State circuitState() {
		return circuitBreaker.getState();
	}

	private Evidence validate(Evidence value, UUID userId, UUID journalId) {
		if (value == null || !"journal-entry-completion-evidence-v1".equals(value.schemaVersion())
				|| !userId.equals(value.subjectUserId()) || !journalId.equals(value.journalId())
				|| !Set.of("RECORDED", "MISSING", "DELETED").contains(value.state())
				|| value.verifiedAt() == null || value.verifiedAt().isAfter(clock.instant())) {
			throw new InvalidEvidenceException();
		}
		if (!"RECORDED".equals(value.state())) {
			if (value.fact() != null) throw new InvalidEvidenceException();
			return value;
		}
		var fact = value.fact();
		if (fact == null || !"JOURNAL_AI".equals(fact.ownerDomain())
				|| !"JOURNAL_ENTRY".equals(fact.kind()) || fact.factId() == null
				|| !"1".equals(fact.factRevision()) || fact.occurredAt() == null
				|| !journalId.equals(fact.factId()) || fact.occurredAt().isAfter(value.verifiedAt())) {
			throw new InvalidEvidenceException();
		}
		return value;
	}

	private static boolean transientFailure(Throwable failure) {
		return failure instanceof RetryableException || failure instanceof FeignException exception
				&& (exception.status() == 408 || exception.status() >= 500);
	}

	private static final class InvalidEvidenceException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}

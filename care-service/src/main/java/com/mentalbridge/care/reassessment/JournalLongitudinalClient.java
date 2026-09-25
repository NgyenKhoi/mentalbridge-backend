package com.mentalbridge.care.reassessment;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.mentalbridge.care.configuration.JournalLongitudinalClientProperties;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.Change;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.Evidence;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.Job;

import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

@Component
public class JournalLongitudinalClient {

	private static final Set<String> PROVIDERS = Set.of("DETERMINISTIC_FAKE", "GEMINI", "OPENAI");
	private static final Set<String> DIRECTIONS = Set.of("MORE_FREQUENT", "LESS_FREQUENT", "SIMILAR",
			"INSUFFICIENT_DATA");
	private static final Set<String> PERIODS = Set.of("PREVIOUS", "CURRENT");
	private static final Set<String> TERMINAL_REASONS = Set.of("CONSENT_REQUIRED", "CONSENT_REVOKED",
			"CONSENT_UNAVAILABLE", "ENTITLEMENT_UNAVAILABLE", "ENTITLEMENT_CHANGED",
			"AUTHORIZATION_CONTEXT_LOST", "SOURCE_REVISION_CHANGED", "SOURCE_DELETED", "PROVIDER_TIMEOUT",
			"PROVIDER_UNAVAILABLE", "INVALID_PROVIDER_RESULT", "INTERNAL_ERROR");

	private final JournalLongitudinalHttpClient httpClient;
	private final CircuitBreaker circuitBreaker;
	private final Retry retry;

	public JournalLongitudinalClient(JournalLongitudinalHttpClient httpClient,
			JournalLongitudinalClientProperties properties) {
		this.httpClient = httpClient;
		Predicate<Throwable> transientFailure = JournalLongitudinalClient::isTransientFailure;
		this.retry = Retry.of("journalLongitudinal", RetryConfig.custom()
				.maxAttempts(properties.maxAttempts())
				.intervalFunction(IntervalFunction.ofExponentialRandomBackoff(properties.retryWait(), 2.0, 0.2))
				.retryOnException(transientFailure)
				.failAfterMaxAttempts(true)
				.build());
		this.circuitBreaker = CircuitBreaker.of("journalLongitudinal", CircuitBreakerConfig.custom()
				.slidingWindowSize(properties.circuitWindowSize())
				.minimumNumberOfCalls(properties.circuitMinimumCalls())
				.failureRateThreshold(properties.circuitFailureRate())
				.waitDurationInOpenState(properties.circuitOpenDuration())
				.permittedNumberOfCallsInHalfOpenState(properties.circuitHalfOpenCalls())
				.recordException(error -> error instanceof InvalidProjectionException || isTransientFailure(error))
				.build());
	}

	public Projection read(UUID userId, UUID analysisId, ReassessmentSummaryView.Period previousPeriod,
			ReassessmentSummaryView.Period currentPeriod, String bearerToken, UUID correlationId) {
		if (userId == null || analysisId == null || previousPeriod == null || currentPeriod == null
				|| bearerToken == null || bearerToken.isBlank() || correlationId == null) {
			throw new IllegalArgumentException("Longitudinal projection context is required");
		}
		Supplier<Evidence> remote = CircuitBreaker.decorateSupplier(circuitBreaker,
				() -> validate(httpClient.get("Bearer " + bearerToken, correlationId.toString(), userId, analysisId,
						"REASSESSMENT_SUMMARY"), analysisId, previousPeriod, currentPeriod));
		try {
			var evidence = Retry.decorateSupplier(retry, remote).get();
			return new Projection(evidence.dataCoverage().sufficientForComparison()
					? "AVAILABLE" : "INSUFFICIENT_DATA", null, evidence);
		}
		catch (FeignException exception) {
			if (exception.status() == 404) return new Projection("UNAVAILABLE", "SOURCE_NOT_FOUND", null);
			if (exception.status() == 401 || exception.status() == 403) {
				return new Projection("UNAVAILABLE", "CONSENT_UNAVAILABLE", null);
			}
			return new Projection("UNAVAILABLE", "DEPENDENCY_UNAVAILABLE", null);
		}
		catch (InvalidProjectionException exception) {
			return new Projection("UNAVAILABLE", "INVALID_PROJECTION", null);
		}
		catch (RuntimeException exception) {
			return new Projection("UNAVAILABLE", "DEPENDENCY_UNAVAILABLE", null);
		}
	}

	public Projection resolveJob(UUID userId, UUID jobId, ReassessmentSummaryView.Period previousPeriod,
			ReassessmentSummaryView.Period currentPeriod, String bearerToken, UUID correlationId) {
		if (userId == null || jobId == null || previousPeriod == null || currentPeriod == null
				|| bearerToken == null || bearerToken.isBlank() || correlationId == null) {
			throw new IllegalArgumentException("Longitudinal job context is required");
		}
		Supplier<Job> remote = CircuitBreaker.decorateSupplier(circuitBreaker,
				() -> validateJob(httpClient.getJob("Bearer " + bearerToken, correlationId.toString(), jobId),
						jobId, previousPeriod, currentPeriod));
		try {
			var job = Retry.decorateSupplier(retry, remote).get();
			if ("RUNNING".equals(job.status())) {
				throw new AnalysisInProgressException();
			}
			if ("FAILED".equals(job.status())) {
				return new Projection(jobId, null, "UNAVAILABLE", job.terminalReason(), null);
			}
			UUID analysisId = job.result().analysisId();
			var projection = read(userId, analysisId, previousPeriod, currentPeriod, bearerToken, correlationId);
			return new Projection(jobId, analysisId, projection.state(), projection.unavailableReason(),
					projection.evidence());
		}
		catch (AnalysisInProgressException exception) {
			throw exception;
		}
		catch (FeignException exception) {
			if (exception.status() == 404) return new Projection(jobId, null, "UNAVAILABLE", "SOURCE_NOT_FOUND", null);
			if (exception.status() == 401 || exception.status() == 403) {
				return new Projection(jobId, null, "UNAVAILABLE", "CONSENT_UNAVAILABLE", null);
			}
			return new Projection(jobId, null, "UNAVAILABLE", "DEPENDENCY_UNAVAILABLE", null);
		}
		catch (InvalidProjectionException exception) {
			return new Projection(jobId, null, "UNAVAILABLE", "INVALID_PROJECTION", null);
		}
		catch (RuntimeException exception) {
			return new Projection(jobId, null, "UNAVAILABLE", "DEPENDENCY_UNAVAILABLE", null);
		}
	}

	CircuitBreaker.State circuitState() {
		return circuitBreaker.getState();
	}

	private Evidence validate(Evidence evidence, UUID analysisId, ReassessmentSummaryView.Period previousPeriod,
			ReassessmentSummaryView.Period currentPeriod) {
		if (evidence == null || !analysisId.equals(evidence.analysisId())
				|| evidence.previousPeriod() == null || evidence.currentPeriod() == null
				|| !previousPeriod.startAt().equals(evidence.previousPeriod().startAt())
				|| !previousPeriod.endAt().equals(evidence.previousPeriod().endAt())
				|| !currentPeriod.startAt().equals(evidence.currentPeriod().startAt())
				|| !currentPeriod.endAt().equals(evidence.currentPeriod().endAt())
				|| evidence.dataCoverage() == null || evidence.dataCoverage().previousPeriodJournalEntryCount() == null
				|| evidence.dataCoverage().currentPeriodJournalEntryCount() == null
				|| evidence.dataCoverage().sufficientForComparison() == null
				|| !validList(evidence.sourceJournalRevisions(), 100)
				|| !validStrings(evidence.contextSignals(), 12) || !validStrings(evidence.emotionIndicators(), 12)
				|| !validStrings(evidence.recurringThemes(), 12) || !validStrings(evidence.preferences(), 12)
				|| !validStrings(evidence.barriers(), 12) || !validStrings(evidence.helpfulPatterns(), 12)
				|| !validList(evidence.changesComparedWithPreviousPeriod(), 24)
				|| !PROVIDERS.contains(evidence.provider()) || blankOrLong(evidence.model(), 128)
				|| !"longitudinal-v1".equals(evidence.promptVersion())
				|| !Integer.valueOf(1).equals(evidence.schemaVersion()) || evidence.createdAt() == null) {
			throw new InvalidProjectionException();
		}

		int previousCount = evidence.dataCoverage().previousPeriodJournalEntryCount();
		int currentCount = evidence.dataCoverage().currentPeriodJournalEntryCount();
		if (previousCount < 0 || previousCount > 100 || currentCount < 0 || currentCount > 100) {
			throw new InvalidProjectionException();
		}
		long previousSources = evidence.sourceJournalRevisions().stream()
				.filter(source -> source != null && "PREVIOUS".equals(source.period())).count();
		long currentSources = evidence.sourceJournalRevisions().stream()
				.filter(source -> source != null && "CURRENT".equals(source.period())).count();
		boolean sourcesValid = evidence.sourceJournalRevisions().stream().allMatch(source -> source != null
				&& source.journalId() != null && source.journalRevision() != null
				&& source.journalRevision() >= 1 && source.journalRevision() <= 200
				&& PERIODS.contains(source.period()));
		boolean sufficient = previousCount >= 3 && currentCount >= 3
				&& Math.max(previousCount, currentCount) <= 2 * Math.min(previousCount, currentCount);
		if (!sourcesValid || previousSources != previousCount || currentSources != currentCount
				|| sufficient != evidence.dataCoverage().sufficientForComparison()
				|| evidence.changesComparedWithPreviousPeriod().stream().anyMatch(this::invalidChange)
				|| !sufficient && evidence.changesComparedWithPreviousPeriod().stream()
						.anyMatch(change -> !"INSUFFICIENT_DATA".equals(change.direction()))) {
			throw new InvalidProjectionException();
		}
		return evidence;
	}

	private Job validateJob(Job job, UUID jobId, ReassessmentSummaryView.Period previousPeriod,
			ReassessmentSummaryView.Period currentPeriod) {
		if (job == null || !jobId.equals(job.jobId()) || job.previousPeriod() == null || job.currentPeriod() == null
				|| !previousPeriod.startAt().equals(job.previousPeriod().startAt())
				|| !previousPeriod.endAt().equals(job.previousPeriod().endAt())
				|| !currentPeriod.startAt().equals(job.currentPeriod().startAt())
				|| !currentPeriod.endAt().equals(job.currentPeriod().endAt())) {
			throw new InvalidProjectionException();
		}
		if ("RUNNING".equals(job.status()) && job.result() == null && job.terminalReason() == null) return job;
		if ("SUCCEEDED".equals(job.status()) && job.result() != null && job.result().analysisId() != null
				&& job.terminalReason() == null) return job;
		if ("FAILED".equals(job.status()) && job.result() == null
				&& TERMINAL_REASONS.contains(job.terminalReason())) return job;
		throw new InvalidProjectionException();
	}

	private boolean invalidChange(Change change) {
		return change == null || blankOrLong(change.signal(), 64) || !DIRECTIONS.contains(change.direction());
	}

	private boolean validStrings(List<String> values, int maximum) {
		return validList(values, maximum) && values.stream().allMatch(value -> !blankOrLong(value, 64));
	}

	private boolean validList(List<?> values, int maximum) {
		return values != null && values.size() <= maximum;
	}

	private boolean blankOrLong(String value, int maximum) {
		return value == null || value.isBlank() || value.length() > maximum;
	}

	private static boolean isTransientFailure(Throwable error) {
		if (hasCause(error, SocketTimeoutException.class) || hasCause(error, ConnectException.class)
				|| error instanceof RetryableException) {
			return true;
		}
		return error instanceof FeignException exception && (exception.status() == 408 || exception.status() >= 500);
	}

	private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
		Throwable current = error;
		while (current != null) {
			if (type.isInstance(current)) return true;
			current = current.getCause();
		}
		return false;
	}

	public record Projection(UUID jobId, UUID analysisId, String state, String unavailableReason, Evidence evidence) {
		public Projection(String state, String unavailableReason, Evidence evidence) {
			this(null, evidence == null ? null : evidence.analysisId(), state, unavailableReason, evidence);
		}
	}

	public static final class AnalysisInProgressException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	private static final class InvalidProjectionException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}

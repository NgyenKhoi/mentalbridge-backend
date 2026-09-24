package com.mentalbridge.care.reassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.configuration.JournalLongitudinalClientProperties;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.Change;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.Coverage;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.Evidence;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.Job;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.JobResult;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.Period;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.SourceRevision;

import feign.FeignException;
import feign.Request;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

class JournalLongitudinalClientTests {

	private static final UUID USER_ID = UUID.fromString("65010000-0000-4000-8000-000000000001");
	private static final UUID ANALYSIS_ID = UUID.fromString("65010000-0000-4000-8000-000000000002");
	private static final UUID JOB_ID = UUID.fromString("65010000-0000-4000-8000-000000000004");
	private static final UUID CORRELATION_ID = UUID.fromString("65010000-0000-4000-8000-000000000003");
	private static final ReassessmentSummaryView.Period PREVIOUS = new ReassessmentSummaryView.Period(
			Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z"));
	private static final ReassessmentSummaryView.Period CURRENT = new ReassessmentSummaryView.Period(
			Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-24T00:00:00Z"));

	@Test
	void forwardsOwnerContextAndAcceptsSufficientProjection() {
		var authorization = new AtomicReference<String>();
		var purpose = new AtomicReference<String>();
		var client = client((auth, correlation, userId, analysisId, requestedPurpose) -> {
			authorization.set(auth);
			purpose.set(requestedPurpose);
			assertThat(correlation).isEqualTo(CORRELATION_ID.toString());
			assertThat(userId).isEqualTo(USER_ID);
			return evidence(true, 3, 3);
		}, 2, 5);

		var result = client.read(USER_ID, ANALYSIS_ID, PREVIOUS, CURRENT, "user-token", CORRELATION_ID);

		assertThat(result.state()).isEqualTo("AVAILABLE");
		assertThat(result.evidence().sourceJournalRevisions()).hasSize(6);
		assertThat(authorization).hasValue("Bearer user-token");
		assertThat(purpose).hasValue("REASSESSMENT_SUMMARY");
	}

	@Test
	void preservesInsufficientDataWithoutInventingADirection() {
		var client = client((auth, correlation, userId, analysisId, purpose) -> evidence(false, 2, 2), 2, 5);

		var result = client.read(USER_ID, ANALYSIS_ID, PREVIOUS, CURRENT, "user-token", CORRELATION_ID);

		assertThat(result.state()).isEqualTo("INSUFFICIENT_DATA");
		assertThat(result.evidence().changesComparedWithPreviousPeriod())
				.extracting(Change::direction).containsOnly("INSUFFICIENT_DATA");
	}

	@Test
	void distinguishesDeletedSourceAndConsentDenialAsUnavailable() {
		var missing = client((auth, correlation, userId, analysisId, purpose) -> { throw httpFailure(404); }, 1, 5);
		var denied = client((auth, correlation, userId, analysisId, purpose) -> { throw httpFailure(403); }, 1, 5);

		assertThat(missing.read(USER_ID, ANALYSIS_ID, PREVIOUS, CURRENT, "user-token", CORRELATION_ID))
				.extracting(JournalLongitudinalClient.Projection::state,
						JournalLongitudinalClient.Projection::unavailableReason)
				.containsExactly("UNAVAILABLE", "SOURCE_NOT_FOUND");
		assertThat(denied.read(USER_ID, ANALYSIS_ID, PREVIOUS, CURRENT, "user-token", CORRELATION_ID))
				.extracting(JournalLongitudinalClient.Projection::state,
						JournalLongitudinalClient.Projection::unavailableReason)
				.containsExactly("UNAVAILABLE", "CONSENT_UNAVAILABLE");
	}

	@Test
	void retriesOneTransientFailureAndFallsBackAfterTheBound() {
		var attempts = new AtomicInteger();
		var recovered = client((auth, correlation, userId, analysisId, purpose) -> {
			if (attempts.incrementAndGet() == 1) throw new RuntimeException(new SocketTimeoutException("synthetic"));
			return evidence(true, 3, 3);
		}, 2, 5);

		assertThat(recovered.read(USER_ID, ANALYSIS_ID, PREVIOUS, CURRENT, "user-token", CORRELATION_ID).state())
				.isEqualTo("AVAILABLE");
		assertThat(attempts).hasValue(2);

		var unavailable = client((auth, correlation, userId, analysisId, purpose) -> {
			throw new RuntimeException(new SocketTimeoutException("synthetic"));
		}, 2, 5);
		assertThat(unavailable.read(USER_ID, ANALYSIS_ID, PREVIOUS, CURRENT, "user-token", CORRELATION_ID))
				.extracting(JournalLongitudinalClient.Projection::state,
						JournalLongitudinalClient.Projection::unavailableReason)
				.containsExactly("UNAVAILABLE", "DEPENDENCY_UNAVAILABLE");
	}

	@Test
	void rejectsMismatchedOrInternallyInconsistentProjectionAndOpensCircuit() {
		var calls = new AtomicInteger();
		var client = client((auth, correlation, userId, analysisId, purpose) -> {
			calls.incrementAndGet();
			return evidence(true, 2, 2);
		}, 1, 2);

		var first = client.read(USER_ID, ANALYSIS_ID, PREVIOUS, CURRENT, "user-token", CORRELATION_ID);
		var second = client.read(USER_ID, ANALYSIS_ID, PREVIOUS, CURRENT, "user-token", CORRELATION_ID);
		var third = client.read(USER_ID, ANALYSIS_ID, PREVIOUS, CURRENT, "user-token", CORRELATION_ID);

		assertThat(first.unavailableReason()).isEqualTo("INVALID_PROJECTION");
		assertThat(second.unavailableReason()).isEqualTo("INVALID_PROJECTION");
		assertThat(third.unavailableReason()).isEqualTo("DEPENDENCY_UNAVAILABLE");
		assertThat(calls).hasValue(2);
		assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	void resolvesSucceededFailedAndRunningJobsWithoutGuessingTheirState() {
		var succeeded = jobClient(new Job(JOB_ID, period(PREVIOUS), period(CURRENT), "SUCCEEDED", null,
				new JobResult(ANALYSIS_ID)), evidence(true, 3, 3));
		var available = succeeded.resolveJob(USER_ID, JOB_ID, PREVIOUS, CURRENT, "user-token", CORRELATION_ID);
		assertThat(available.jobId()).isEqualTo(JOB_ID);
		assertThat(available.analysisId()).isEqualTo(ANALYSIS_ID);
		assertThat(available.state()).isEqualTo("AVAILABLE");

		var failed = jobClient(new Job(JOB_ID, period(PREVIOUS), period(CURRENT), "FAILED",
				"PROVIDER_TIMEOUT", null), null);
		assertThat(failed.resolveJob(USER_ID, JOB_ID, PREVIOUS, CURRENT, "user-token", CORRELATION_ID))
				.extracting(JournalLongitudinalClient.Projection::state,
						JournalLongitudinalClient.Projection::unavailableReason)
				.containsExactly("UNAVAILABLE", "PROVIDER_TIMEOUT");

		var running = jobClient(new Job(JOB_ID, period(PREVIOUS), period(CURRENT), "RUNNING", null, null), null);
		assertThatThrownBy(() -> running.resolveJob(USER_ID, JOB_ID, PREVIOUS, CURRENT,
				"user-token", CORRELATION_ID))
				.isInstanceOf(JournalLongitudinalClient.AnalysisInProgressException.class);
	}

	@Test
	void rejectsConfigurationThatCanOutliveTheCallerBudget() {
		assertThatThrownBy(() -> new JournalLongitudinalClientProperties("", Duration.ofMillis(500),
				Duration.ofSeconds(2), 2, Duration.ofMillis(100), 10, 5, 50,
				Duration.ofSeconds(10), 2))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Journal/AI call budget must not exceed PT2.5S");

		var bounded = new JournalLongitudinalClientProperties("", Duration.ofMillis(200),
				Duration.ofMillis(800), 2, Duration.ofMillis(100), 10, 5, 50,
				Duration.ofSeconds(10), 2);
		assertThat(bounded.connectTimeout()).isEqualTo(Duration.ofMillis(200));
		assertThat(bounded.readTimeout()).isEqualTo(Duration.ofMillis(800));
	}

	private JournalLongitudinalClient client(AnalysisHttp analysis, int attempts, int minimumCalls) {
		JournalLongitudinalHttpClient http = new JournalLongitudinalHttpClient() {
			@Override
			public JournalLongitudinalContract.Job getJob(String authorization, String correlationId, UUID jobId) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Evidence get(String authorization, String correlationId, UUID userId, UUID analysisId,
					String purpose) {
				return analysis.get(authorization, correlationId, userId, analysisId, purpose);
			}
		};
		return configuredClient(http, attempts, minimumCalls);
	}

	private JournalLongitudinalClient jobClient(Job job, Evidence projection) {
		JournalLongitudinalHttpClient http = new JournalLongitudinalHttpClient() {
			@Override
			public Job getJob(String authorization, String correlationId, UUID jobId) {
				return job;
			}

			@Override
			public Evidence get(String authorization, String correlationId, UUID userId, UUID analysisId,
					String purpose) {
				if (projection == null) throw new AssertionError("Analysis must not be read for this job state");
				return projection;
			}
		};
		return configuredClient(http, 1, 5);
	}

	private JournalLongitudinalClient configuredClient(JournalLongitudinalHttpClient http, int attempts,
			int minimumCalls) {
		return new JournalLongitudinalClient(http, new JournalLongitudinalClientProperties("", Duration.ofMillis(200),
				Duration.ofMillis(800), attempts, Duration.ofMillis(1), minimumCalls, minimumCalls, 50,
				Duration.ofSeconds(10), 1));
	}

	private Period period(ReassessmentSummaryView.Period value) {
		return new Period(value.startAt(), value.endAt());
	}

	@FunctionalInterface
	private interface AnalysisHttp {
		Evidence get(String authorization, String correlationId, UUID userId, UUID analysisId, String purpose);
	}

	private Evidence evidence(boolean sufficient, int previousCount, int currentCount) {
		var sources = new java.util.ArrayList<SourceRevision>();
		for (int index = 0; index < previousCount; index++) {
			sources.add(new SourceRevision(UUID.randomUUID(), 1, "PREVIOUS"));
		}
		for (int index = 0; index < currentCount; index++) {
			sources.add(new SourceRevision(UUID.randomUUID(), 1, "CURRENT"));
		}
		return new Evidence(ANALYSIS_ID, new Period(PREVIOUS.startAt(), PREVIOUS.endAt()),
				new Period(CURRENT.startAt(), CURRENT.endAt()), sources, List.of("work-pressure"), List.of("stress"),
				List.of("deadlines"), List.of(new Change("stress", sufficient ? "MORE_FREQUENT" : "INSUFFICIENT_DATA")),
				List.of(), List.of("low-energy"), List.of(), new Coverage(previousCount, currentCount, sufficient),
				"DETERMINISTIC_FAKE", "deterministic-longitudinal-v1", "longitudinal-v1", 1,
				Instant.parse("2026-09-24T00:00:00Z"));
	}

	private FeignException httpFailure(int status) {
		Request request = Request.create(Request.HttpMethod.GET, "/internal/v1/users/u/longitudinal-analyses/a",
				Map.of(), null, StandardCharsets.UTF_8, null);
		Response response = Response.builder().request(request).status(status).reason("synthetic").headers(Map.of())
				.build();
		return FeignException.errorStatus("getLongitudinalEvidenceForCare", response);
	}
}

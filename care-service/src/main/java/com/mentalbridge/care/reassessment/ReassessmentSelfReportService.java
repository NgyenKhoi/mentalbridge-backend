package com.mentalbridge.care.reassessment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.reassessment.ReassessmentSelfReportStore.StoredSelfReport;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.Period;
import com.mentalbridge.care.shared.ApiException;

@Service
public class ReassessmentSelfReportService {

	static final String SOURCE_VERSION = "reassessment-self-report-v1";
	private static final Duration MINIMUM_PERIOD = Duration.ofDays(7);
	private static final Duration MAXIMUM_PERIOD = Duration.ofDays(31);

	private final ReassessmentSelfReportStore reports;
	private final Clock clock;

	public ReassessmentSelfReportService(ReassessmentSelfReportStore reports, Clock clock) {
		this.reports = reports;
		this.clock = clock;
	}

	public ReassessmentSelfReportView create(UUID userId, String key, CreateCommand command) {
		validatePeriod(command.currentPeriod());
		var normalized = normalize(command);
		String requestHash = hash(normalized);
		var replay = reports.findByRequest(userId, key);
		if (replay.isPresent()) return replay(replay.get(), requestHash);
		var stored = reports.persist(UUID.randomUUID(), userId, key, requestHash, normalized.currentPeriod(),
				normalized.currentExperience(), normalized.helpfulContext(), normalized.difficultContext(), clock.instant());
		return replay(stored, requestHash);
	}

	public ReassessmentSelfReportView current(UUID userId) {
		return reports.current(userId).map(this::view).orElseThrow(this::notFound);
	}

	public ReassessmentSelfReportView replace(UUID userId, UUID id, long expectedVersion, ReplaceCommand command) {
		var existing = reports.find(userId, id).orElseThrow(this::notFound);
		if (existing.deleted()) throw notFound();
		var normalized = normalize(command);
		return reports.replace(userId, id, expectedVersion, normalized.currentExperience(), normalized.helpfulContext(),
				normalized.difficultContext(), clock.instant()).map(this::view).orElseThrow(this::versionMismatch);
	}

	public void delete(UUID userId, UUID id, long expectedVersion) {
		var existing = reports.find(userId, id).orElseThrow(this::notFound);
		if (existing.deleted()) throw notFound();
		if (!reports.delete(userId, id, expectedVersion, clock.instant())) throw versionMismatch();
	}

	StoredSelfReport evidence(UUID userId, UUID id) {
		return reports.find(userId, id).orElseThrow(this::notFound);
	}

	private ReassessmentSelfReportView replay(StoredSelfReport stored, String hash) {
		if (!stored.requestHash().equals(hash)) {
			throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
					"Idempotency-Key was already used with a different reassessment self-report");
		}
		if (stored.deleted()) throw notFound();
		return view(stored);
	}

	private ReassessmentSelfReportView view(StoredSelfReport stored) {
		return new ReassessmentSelfReportView(stored.id(), stored.sourceVersion(),
				new Period(stored.periodStart(), stored.periodEnd()), stored.currentExperience(), stored.helpfulContext(),
				stored.difficultContext(), stored.version(), stored.authoredAt(), stored.updatedAt());
	}

	private CreateCommand normalize(CreateCommand command) {
		return new CreateCommand(command.currentPeriod(), command.currentExperience(), normalized(command.helpfulContext()),
				normalized(command.difficultContext()));
	}

	private ReplaceCommand normalize(ReplaceCommand command) {
		return new ReplaceCommand(command.currentExperience(), normalized(command.helpfulContext()),
				normalized(command.difficultContext()));
	}

	private String normalized(String value) {
		if (value == null) return null;
		String result = value.trim();
		return result.isEmpty() ? null : result;
	}

	private void validatePeriod(Period period) {
		Duration duration = Duration.between(period.startAt(), period.endAt());
		if (duration.compareTo(MINIMUM_PERIOD) < 0 || duration.compareTo(MAXIMUM_PERIOD) > 0
				|| period.endAt().isAfter(clock.instant())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REASSESSMENT_PERIOD",
					"Self-report period must end in the past and span between 7 and 31 days");
		}
	}

	private String hash(CreateCommand command) {
		String value = command.currentPeriod().startAt() + "|" + command.currentPeriod().endAt() + "|"
				+ command.currentExperience() + "|" + command.helpfulContext() + "|" + command.difficultContext();
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "REASSESSMENT_SELF_REPORT_NOT_FOUND",
				"Reassessment self-report was not found");
	}

	private ApiException versionMismatch() {
		return new ApiException(HttpStatus.PRECONDITION_FAILED, "REASSESSMENT_SELF_REPORT_VERSION_MISMATCH",
				"Reassessment self-report version does not match If-Match");
	}

	public record CreateCommand(Period currentPeriod, String currentExperience, String helpfulContext,
			String difficultContext) { }
	public record ReplaceCommand(String currentExperience, String helpfulContext, String difficultContext) { }
}

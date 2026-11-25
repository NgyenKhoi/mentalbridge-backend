package com.mentalbridge.identity.reporting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.identity.idempotency.IdempotencyConflictException;

@Service
public class PlatformReportService {

	private static final int DEFAULT_LIMIT = 20;
	private static final int MAX_LIMIT = 50;
	private static final int MAX_PERIOD_DAYS = 366;

	private final PlatformReportJobRepository jobs;
	private final PlatformReportArtifactRepository artifacts;
	private final Clock clock;

	public PlatformReportService(PlatformReportJobRepository jobs, PlatformReportArtifactRepository artifacts, Clock clock) {
		this.jobs = jobs;
		this.artifacts = artifacts;
		this.clock = clock;
	}

	public List<ReportTypeResponse> catalogue() {
		return List.of(new ReportTypeResponse(PlatformReportType.ACCOUNT_ACTIVITY,
				"Account activity", "Aggregate account creations by role and current lifecycle state.",
				"platform-account-activity-report-v1", MAX_PERIOD_DAYS));
	}

	@Transactional
	public ReportResponse request(UUID actorId, ReportRequest request, String idempotencyKey) {
		return enqueue(actorId, request, idempotencyKey, null, null);
	}

	@Transactional
	public ReportResponse requestScheduled(UUID actorId, ReportRequest request, String idempotencyKey,
			UUID scheduleId, Instant scheduledFor) {
		return enqueue(actorId, request, idempotencyKey, scheduleId, scheduledFor);
	}

	private ReportResponse enqueue(UUID actorId, ReportRequest request, String idempotencyKey,
			UUID scheduleId, Instant scheduledFor) {
		validateActor(actorId);
		validateRequest(request);
		String requestHash = hash(request.reportType() + "|" + request.periodStart() + "|" + request.periodEnd());
		var existing = jobs.findByRequestedByAndIdempotencyKey(actorId, idempotencyKey);
		if (existing.isPresent()) {
			if (!existing.get().requestHash().equals(requestHash)) throw new IdempotencyConflictException();
			return response(existing.get());
		}
		var job = PlatformReportJobEntity.queued(request.reportType(), request.periodStart(), request.periodEnd(),
				actorId, clock.instant(), idempotencyKey, requestHash, null);
		if (scheduleId != null) job.schedule(scheduleId, scheduledFor);
		return response(jobs.save(job));
	}

	@Transactional
	public ReportResponse retry(UUID actorId, UUID reportId, String idempotencyKey) {
		validateActor(actorId);
		var source = jobs.findById(reportId).orElseThrow(() -> new PlatformReportNotFoundException(reportId));
		if (source.status() != PlatformReportStatus.FAILED && source.status() != PlatformReportStatus.STALE) {
			throw new InvalidPlatformReportRequestException("Only failed or stale reports can be retried");
		}
		String requestHash = hash("retry|" + reportId);
		var existing = jobs.findByRequestedByAndIdempotencyKey(actorId, idempotencyKey);
		if (existing.isPresent()) {
			if (!existing.get().requestHash().equals(requestHash)) throw new IdempotencyConflictException();
			return response(existing.get());
		}
		var retry = PlatformReportJobEntity.queued(source.reportType(), source.periodStart(), source.periodEnd(), actorId,
				clock.instant(), idempotencyKey, requestHash, source.id());
		return response(jobs.save(retry));
	}

	@Transactional(readOnly = true)
	public ReportPage history(String cursor, Integer requestedLimit) {
		int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
		if (limit < 1 || limit > MAX_LIMIT) throw new InvalidPlatformReportRequestException("Limit is invalid");
		Cursor after = cursor == null ? null : decodeCursor(cursor);
		var pageRequest = PageRequest.of(0, limit + 1);
		var found = after == null ? jobs.browseFirstPage(pageRequest)
				: jobs.browseAfter(after.requestedAt(), after.id(), pageRequest);
		boolean hasNext = found.size() > limit;
		var selected = found.subList(0, Math.min(limit, found.size()));
		String nextCursor = hasNext ? encodeCursor(selected.get(selected.size() - 1)) : null;
		return new ReportPage(selected.stream().map(this::response).toList(), nextCursor);
	}

	@Transactional(readOnly = true)
	public ArtifactDownload download(UUID reportId) {
		var job = jobs.findById(reportId).orElseThrow(() -> new PlatformReportNotFoundException(reportId));
		if (job.status() != PlatformReportStatus.COMPLETED) {
			throw new PlatformReportNotDownloadableException("The report is not completed");
		}
		var artifact = artifacts.findById(reportId)
				.orElseThrow(() -> new PlatformReportArtifactExpiredException(reportId));
		if (!artifact.retainedUntil().isAfter(clock.instant())) {
			throw new PlatformReportArtifactExpiredException(reportId);
		}
		return new ArtifactDownload(artifact.fileName(), artifact.mediaType(), artifact.content(),
				artifact.contentSha256(), artifact.retainedUntil());
	}

	private ReportResponse response(PlatformReportJobEntity job) {
		var artifact = job.status() == PlatformReportStatus.COMPLETED ? artifacts.findById(job.id()).orElse(null) : null;
		boolean downloadable = artifact != null && artifact.retainedUntil().isAfter(clock.instant());
		return new ReportResponse(job.id(), job.reportType(), job.scopeVersion(), job.periodStart(), job.periodEnd(),
				job.requestedBy(), job.requestedAt(), job.status(), job.sourceVersions(), job.retryOf(), job.startedAt(),
				job.completedAt(), job.failedAt(), job.failureCode(), downloadable,
				artifact == null ? null : artifact.fileName(), artifact == null ? null : artifact.mediaType(),
				artifact == null ? null : artifact.contentLength(), artifact == null ? null : artifact.contentSha256(),
				artifact == null ? null : artifact.retainedUntil());
	}

	private void validateRequest(ReportRequest request) {
		if (request == null || request.reportType() == null || request.periodStart() == null || request.periodEnd() == null) {
			throw new InvalidPlatformReportRequestException("Report scope is required");
		}
		long days = ChronoUnit.DAYS.between(request.periodStart(), request.periodEnd()) + 1;
		if (days < 1 || days > MAX_PERIOD_DAYS || request.periodEnd().isAfter(LocalDate.now(clock))) {
			throw new InvalidPlatformReportRequestException("Report period must be between 1 and 366 completed calendar days");
		}
	}

	private void validateActor(UUID actorId) {
		if (actorId == null) throw new InvalidPlatformReportRequestException("Authenticated administrator is required");
	}

	private String encodeCursor(PlatformReportJobEntity job) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(
				(job.requestedAt() + "|" + job.id()).getBytes(StandardCharsets.UTF_8));
	}

	private Cursor decodeCursor(String cursor) {
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = decoded.split("\\|", -1);
			if (parts.length != 2) throw new IllegalArgumentException();
			return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
		}
		catch (IllegalArgumentException | DateTimeParseException exception) {
			throw new InvalidPlatformReportRequestException("Cursor is invalid");
		}
	}

	static String hash(String value) {
		return hash(value.getBytes(StandardCharsets.UTF_8));
	}

	static String hash(byte[] value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	public record ReportRequest(PlatformReportType reportType, LocalDate periodStart, LocalDate periodEnd) { }
	public record ReportTypeResponse(PlatformReportType reportType, String label, String description,
			String scopeVersion, int maximumPeriodDays) { }
	public record ReportPage(List<ReportResponse> items, String nextCursor) { }
	public record ReportResponse(UUID reportId, PlatformReportType reportType, String scopeVersion,
			LocalDate periodStart, LocalDate periodEnd, UUID requestedBy, Instant requestedAt,
			PlatformReportStatus status, Map<String, String> sourceVersions, UUID retryOf, Instant startedAt,
			Instant completedAt, Instant failedAt, String failureCode, boolean downloadable, String fileName,
			String mediaType, Long contentLength, String contentSha256, Instant retainedUntil) { }
	public record ArtifactDownload(String fileName, String mediaType, byte[] content, String contentSha256,
			Instant retainedUntil) { }
	private record Cursor(Instant requestedAt, UUID id) { }

}

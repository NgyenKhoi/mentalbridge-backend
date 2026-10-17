package com.mentalbridge.identity.reporting;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.account.AccountRepository;
import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.account.RoleCode;

@Service
public class PlatformReportProcessor {

	private static final Duration RETENTION = Duration.ofDays(30);

	private final PlatformReportJobRepository jobs;
	private final PlatformReportArtifactRepository artifacts;
	private final AccountRepository accounts;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public PlatformReportProcessor(PlatformReportJobRepository jobs, PlatformReportArtifactRepository artifacts,
			AccountRepository accounts, ObjectMapper objectMapper, Clock clock) {
		this.jobs = jobs;
		this.artifacts = artifacts;
		this.accounts = accounts;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	@Transactional
	public boolean processNext() {
		var optional = jobs.findFirstByStatusOrderByRequestedAtAscIdAsc(PlatformReportStatus.QUEUED);
		if (optional.isEmpty()) return false;
		var job = optional.get();
		var now = clock.instant();
		job.start(now);
		if (!"platform-account-activity-report-v1".equals(job.scopeVersion())
				|| !"identity-account-projection-v1".equals(job.sourceVersions().get("identityAccounts"))) {
			job.stale(now, "SOURCE_VERSION_UNAVAILABLE");
			return true;
		}
		try {
			byte[] content = generate(job, now);
			String fileName = "account-activity-" + job.periodStart() + "-to-" + job.periodEnd() + ".json";
			artifacts.save(new PlatformReportArtifactEntity(job.id(), "application/json", fileName, content,
					PlatformReportService.hash(content), now,
					now.plus(RETENTION)));
			job.complete(now);
		}
		catch (JsonProcessingException | RuntimeException exception) {
			job.fail(now, "GENERATION_FAILED");
		}
		return true;
	}

	@Transactional
	public long purgeExpiredArtifacts() {
		return artifacts.deleteByRetainedUntilBefore(clock.instant());
	}

	private byte[] generate(PlatformReportJobEntity job, java.time.Instant generatedAt) throws JsonProcessingException {
		var start = job.periodStart().atStartOfDay().toInstant(ZoneOffset.UTC);
		var endExclusive = job.periodEnd().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
		var breakdown = new ArrayList<Map<String, Object>>();
		long total = 0;
		for (Object[] row : accounts.aggregateCreatedAccounts(start, endExclusive)) {
			long count = (Long) row[2];
			total += count;
			var item = new LinkedHashMap<String, Object>();
			item.put("role", ((RoleCode) row[0]).name());
			item.put("status", ((AccountStatus) row[1]).name());
			item.put("count", count);
			breakdown.add(item);
		}
		var provenance = new LinkedHashMap<String, Object>();
		provenance.put("reportId", job.id());
		provenance.put("reportType", job.reportType());
		provenance.put("scopeVersion", job.scopeVersion());
		provenance.put("sourceVersions", job.sourceVersions());
		provenance.put("periodStart", job.periodStart());
		provenance.put("periodEnd", job.periodEnd());
		provenance.put("requestedBy", job.requestedBy());
		provenance.put("requestedAt", job.requestedAt());
		provenance.put("generatedAt", generatedAt);
		var aggregate = new LinkedHashMap<String, Object>();
		aggregate.put("createdAccountCount", total);
		aggregate.put("createdAccountsByRoleAndStatus", breakdown);
		return objectMapper.writeValueAsBytes(Map.of("provenance", provenance, "aggregate", aggregate));
	}

}

package com.mentalbridge.identity.reporting;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "mentalbridge.identity.platform-reporting", name = "enabled", havingValue = "true",
		matchIfMissing = true)
class PlatformReportScheduler {

	private final PlatformReportProcessor processor;
	private final ReportScheduleService schedules;

	PlatformReportScheduler(PlatformReportProcessor processor, ReportScheduleService schedules) {
		this.processor = processor;
		this.schedules = schedules;
	}

	@Scheduled(fixedDelayString = "${mentalbridge.identity.platform-reporting.poll-interval:PT2S}")
	void generate() {
		schedules.enqueueNext();
		processor.processNext();
	}

	@Scheduled(fixedDelayString = "${mentalbridge.identity.platform-reporting.retention-sweep-interval:PT1H}")
	void purgeExpiredArtifacts() {
		processor.purgeExpiredArtifacts();
	}

}

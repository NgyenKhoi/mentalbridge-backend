package com.mentalbridge.consultation.video;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class VideoEvidenceRetention {

	private final VideoEvidenceService evidence;

	VideoEvidenceRetention(VideoEvidenceService evidence) { this.evidence = evidence; }

	@Scheduled(fixedDelay = 3600000)
	void cleanup() { for (var id : evidence.expiredRooms()) evidence.purge(id); }
}

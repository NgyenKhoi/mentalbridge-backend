package com.mentalbridge.care.supportguide;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.profile.UserProfileRepository;
import com.mentalbridge.care.shared.ApiException;

@Service
class SupportGuideWriter {

	private final SupportGuideRepository guides;
	private final SupportGuideResourceRepository resources;
	private final UserProfileRepository profiles;

	SupportGuideWriter(SupportGuideRepository guides, SupportGuideResourceRepository resources,
			UserProfileRepository profiles) {
		this.guides = guides;
		this.resources = resources;
		this.profiles = profiles;
	}

	@Transactional
	StoredGuide persist(UUID userId, String idempotencyKey, String requestHash, Draft draft) {
		if (profiles.findByIdForUpdate(userId).isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Care profile was not found");
		}
		var replay = guides.findRequest(userId, idempotencyKey);
		if (replay.isPresent()) {
			if (!replay.orElseThrow().getRequestHash().equals(requestHash)) {
				throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
						"Idempotency-Key was already used with different screening evidence");
			}
			return required(userId, replay.orElseThrow().getGuideId());
		}
		var existing = guides.findByUserIdAndSupportEvaluationIdAndGuidePolicyVersion(
				userId, draft.supportEvaluationId(), SupportGuideService.GUIDE_POLICY_VERSION);
		if (existing.isPresent()) {
			guides.insertRequest(userId, idempotencyKey, requestHash, existing.orElseThrow().id(), draft.generatedAt());
			return stored(existing.orElseThrow());
		}

		var guide = new SupportGuideEntity(UUID.randomUUID(), userId, draft.supportEvaluationId(),
				SupportGuideService.GUIDE_POLICY_VERSION, draft.generatedAt(), draft.explanationCode(),
				draft.explanationText(), draft.safetyStatus(), draft.safetyReasonCode(), draft.safetyPolicyVersion(),
				draft.safetyGuidanceCode(), draft.safetyGuidance(), draft.resourceStatus(),
				draft.resourcePolicyVersion(), draft.resourcesResolvedAt(), draft.phrasingStatus());
		guides.saveAndFlush(guide);
		short ordinal = 1;
		for (ResourceDraft resource : draft.resources()) {
			resources.save(new SupportGuideResourceEntity(UUID.randomUUID(), guide.id(), ordinal++,
					resource.resourceId(), resource.contentVersion(), resource.publicationId(), resource.domain(),
					resource.role(), resource.category(), resource.title(), resource.summary(), resource.externalUrl()));
		}
		resources.flush();
		guides.insertRequest(userId, idempotencyKey, requestHash, guide.id(), draft.generatedAt());
		return stored(guide);
	}

	@Transactional(readOnly = true)
	StoredGuide required(UUID userId, UUID guideId) {
		var guide = guides.findByIdAndUserId(guideId, userId).orElseThrow(() -> new ApiException(
				HttpStatus.NOT_FOUND, "SUPPORT_GUIDE_NOT_FOUND", "Support Guide was not found"));
		return stored(guide);
	}

	@Transactional(readOnly = true)
	SupportGuideRepository.RequestRow request(UUID userId, String key) {
		return guides.findRequest(userId, key).orElse(null);
	}

	@Transactional(readOnly = true)
	List<StoredGuide> history(UUID userId, Instant beforeTime, UUID beforeId, int limit) {
		var page = org.springframework.data.domain.PageRequest.of(0, limit + 1);
		var rows = beforeTime == null
				? guides.findByUserIdOrderByGeneratedAtDescIdDesc(userId, page)
				: guides.historyBefore(userId, beforeTime, beforeId, page);
		return rows.stream().map(this::stored).toList();
	}

	private StoredGuide stored(SupportGuideEntity guide) {
		return new StoredGuide(guide, resources.findBySupportGuideIdOrderByOrdinal(guide.id()));
	}

	record Draft(UUID supportEvaluationId, Instant generatedAt, String explanationCode, String explanationText,
			String safetyStatus, String safetyReasonCode, String safetyPolicyVersion, String safetyGuidanceCode,
			String safetyGuidance, String resourceStatus, String resourcePolicyVersion, Instant resourcesResolvedAt,
			String phrasingStatus, List<ResourceDraft> resources) { }

	record ResourceDraft(UUID resourceId, long contentVersion, UUID publicationId, String domain, String role,
			String category, String title, String summary, String externalUrl) { }

	record StoredGuide(SupportGuideEntity guide, List<SupportGuideResourceEntity> resources) { }
}

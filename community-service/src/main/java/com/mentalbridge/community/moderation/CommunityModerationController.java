package com.mentalbridge.community.moderation;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.community.moderation.CommunityModerationModels.CaseState;
import com.mentalbridge.community.moderation.CommunityModerationModels.CreateModerationActionRequest;
import com.mentalbridge.community.moderation.CommunityModerationModels.CreateReportRequest;
import com.mentalbridge.community.moderation.CommunityModerationModels.ModerationCase;
import com.mentalbridge.community.moderation.CommunityModerationModels.Priority;
import com.mentalbridge.community.moderation.CommunityModerationModels.TargetType;
import com.mentalbridge.community.shared.CommunityApiException;

@RestController
@RequestMapping("/api/v1/community")
class CommunityModerationController {

	private final CommunityModerationService moderation;

	CommunityModerationController(CommunityModerationService moderation) {
		this.moderation = moderation;
	}

	@PostMapping("/reports")
	ResponseEntity<Void> report(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody CreateReportRequest request) {
		moderation.report(subject(jwt), idempotencyKey, request);
		return ResponseEntity.status(HttpStatus.ACCEPTED).build();
	}

	@PutMapping("/hidden-content/{targetType}/{targetId}")
	ResponseEntity<Void> hide(@AuthenticationPrincipal Jwt jwt, @PathVariable TargetType targetType,
			@PathVariable UUID targetId) {
		moderation.hide(subject(jwt), targetType, targetId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/hidden-content/{targetType}/{targetId}")
	ResponseEntity<Void> unhide(@AuthenticationPrincipal Jwt jwt, @PathVariable TargetType targetType,
			@PathVariable UUID targetId) {
		moderation.unhide(subject(jwt), targetType, targetId);
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/blocks/{communityProfileId}")
	ResponseEntity<Void> block(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID communityProfileId) {
		moderation.block(subject(jwt), communityProfileId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/blocks/{communityProfileId}")
	ResponseEntity<Void> unblock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID communityProfileId) {
		moderation.unblock(subject(jwt), communityProfileId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/admin/moderation-cases")
	List<ModerationCase> list(@RequestParam(required = false) CaseState state,
			@RequestParam(required = false) TargetType targetType,
			@RequestParam(required = false) Priority priority) {
		return moderation.list(state, targetType, priority);
	}

	@GetMapping("/admin/moderation-cases/{caseId}")
	ModerationCase detail(@PathVariable UUID caseId) {
		return moderation.get(caseId);
	}

	@PostMapping("/admin/moderation-cases/{caseId}/actions")
	ResponseEntity<ModerationCase> act(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID caseId,
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			@RequestBody CreateModerationActionRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(moderation.act(subject(jwt), caseId, idempotencyKey, request, correlationId));
	}

	private UUID subject(Jwt jwt) {
		try {
			return UUID.fromString(jwt.getSubject());
		}
		catch (IllegalArgumentException exception) {
			throw CommunityApiException.invalidSubject();
		}
	}
}

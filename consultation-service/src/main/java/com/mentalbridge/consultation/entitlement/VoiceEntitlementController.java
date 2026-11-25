package com.mentalbridge.consultation.entitlement;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

@RestController
public class VoiceEntitlementController {

	private final CurrentServiceEntitlementService entitlements;
	private final Clock clock;
	private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	public VoiceEntitlementController(CurrentServiceEntitlementService entitlements, Clock clock) {
		this.entitlements = entitlements;
		this.clock = clock;
	}

	@PostMapping(VoiceEntitlementAuthenticationFilter.PATH)
	ResponseEntity<Decision> current(@RequestBody String body) {
		JsonNode request;
		try {
			request = json.readTree(body);
		}
		catch (java.io.IOException exception) {
			throw new InvalidRequest();
		}
		if (request == null) throw new InvalidRequest();
		var fields = new java.util.HashSet<String>();
		request.fieldNames().forEachRemaining(fields::add);
		if (!request.isObject() || !fields.equals(Set.of("ownerId", "voiceSessionId", "purpose"))
				|| !request.path("purpose").isTextual()
				|| !"AI_COMPANION_VOICE".equals(request.path("purpose").textValue())) {
			throw new InvalidRequest();
		}
		var ownerId = uuid(request.path("ownerId"));
		var sessionId = uuid(request.path("voiceSessionId"));
		var decision = entitlements.current(ownerId);
		var checkedAt = clock.instant();
		if (decision == null || !ownerId.equals(decision.accountId()) || decision.packageCode() == null
				|| decision.source() == null || decision.version() < 0 || decision.decidedAt() == null) {
			throw new IllegalStateException("Entitlement decision is unavailable");
		}
		var source = decision.packageCode() == ServicePackage.FREE ? EntitlementSource.DEFAULT_FREE : decision.source();
		var version = decision.policyVersion() + ":" + source + ":" + decision.version();
		if (decision.policyVersion() == null || decision.policyVersion().isBlank() || version.length() > 128) {
			throw new IllegalStateException("Entitlement provenance is unavailable");
		}
		if (decision.packageCode() == ServicePackage.FREE) {
			return ResponseEntity.ok().header("Cache-Control", "no-store").body(new Decision(ownerId, sessionId,
					"AI_COMPANION_VOICE", ServicePackage.FREE, EntitlementSource.DEFAULT_FREE,
					version, checkedAt, null));
		}
		if (decision.source() == EntitlementSource.DEFAULT_FREE || decision.effectiveFrom() == null
				|| decision.effectiveFrom().isAfter(checkedAt) || decision.effectiveUntil() == null
				|| !decision.effectiveUntil().isAfter(checkedAt)) {
			throw new IllegalStateException("Entitlement decision is unavailable");
		}
		return ResponseEntity.ok().header("Cache-Control", "no-store").body(new Decision(ownerId, sessionId,
				"AI_COMPANION_VOICE", decision.packageCode(), decision.source(), version,
				checkedAt, decision.effectiveUntil()));
	}

	private UUID uuid(JsonNode value) {
		if (!value.isTextual() || !value.textValue().matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
			throw new InvalidRequest();
		}
		return UUID.fromString(value.textValue());
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Decision(UUID ownerId, UUID voiceSessionId, String purpose, ServicePackage packageCode,
			EntitlementSource source, String entitlementVersion, Instant checkedAt, Instant validUntil) { }

	static final class InvalidRequest extends RuntimeException { }
}

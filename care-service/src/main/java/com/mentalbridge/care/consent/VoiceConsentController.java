package com.mentalbridge.care.consent;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
public class VoiceConsentController {
	private static final String UUID_PATTERN = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
	private final com.fasterxml.jackson.databind.ObjectReader json = new ObjectMapper()
			.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readerFor(JsonNode.class)
			.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	private final VoiceConsentService consents;

	public VoiceConsentController(VoiceConsentService consents) { this.consents = consents; }

	@GetMapping("/api/v1/ai-voice-processing-disclosure")
	VoiceConsentService.Disclosure disclosure(@AuthenticationPrincipal Jwt jwt) {
		subject(jwt);
		return consents.disclosure();
	}

	@GetMapping("/api/v1/consents/ai-voice-processing")
	VoiceConsentService.Consent current(@AuthenticationPrincipal Jwt jwt) { return consents.current(subject(jwt)); }

	@PostMapping("/api/v1/consents/ai-voice-processing")
	VoiceConsentService.Consent record(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") @Pattern(regexp = UUID_PATTERN) String idempotencyKey,
			@RequestBody String body) {
		var command = object(body, Set.of("consentVersion", "granted"));
		if (!VoiceConsentService.VERSION.equals(text(command, "consentVersion")) || !command.path("granted").isBoolean()) {
			throw invalid();
		}
		return consents.record(subject(jwt), idempotencyKey, command.path("consentVersion").textValue(),
				command.path("granted").booleanValue());
	}

	@PostMapping("/internal/v1/ai-companion/voice-consent-authorizations")
	VoiceConsentService.Authorization authorize(@RequestBody String body) {
		var command = object(body, Set.of("ownerId", "voiceSessionId", "purpose"));
		if (!VoiceConsentService.PURPOSE.equals(text(command, "purpose"))) throw invalid();
		return consents.authorize(uuid(text(command, "ownerId")), uuid(text(command, "voiceSessionId")));
	}

	private JsonNode object(String body, Set<String> fields) {
		try {
			JsonNode value = json.readValue(body);
			if (value == null || !value.isObject() || value.size() != fields.size() || !fields.stream().allMatch(value::has)) throw invalid();
			return value;
		}
		catch (java.io.IOException exception) { throw invalid(); }
	}

	private String text(JsonNode value, String field) {
		if (!value.path(field).isTextual()) throw invalid();
		return value.path(field).textValue();
	}

	private UUID uuid(String value) {
		if (!value.matches(UUID_PATTERN)) throw invalid();
		return UUID.fromString(value);
	}

	private UUID subject(Jwt jwt) {
		if (jwt == null || jwt.getSubject() == null || !jwt.getSubject().matches(UUID_PATTERN)) {
			throw new VoiceApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "The authenticated subject is invalid");
		}
		return UUID.fromString(jwt.getSubject());
	}

	private VoiceApiException invalid() {
		return new VoiceApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "The voice request is invalid");
	}
}

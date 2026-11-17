package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.mentalbridge.care.shared.ApiException;

@RestController
public class SupportPlanReminderEligibilityController {
	public static final String PATH = "/internal/v1/support-plan-reminder-eligibility";
	private static final Set<String> FIELDS = Set.of("ownerAccountId", "asOf");
	private final ObjectReader json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
			.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	private final SupportPlanReminderEligibilityService eligibility;

	SupportPlanReminderEligibilityController(SupportPlanReminderEligibilityService eligibility) {
		this.eligibility = eligibility;
	}

	@PostMapping(PATH)
	Eligibility check(@RequestBody String raw) {
		JsonNode body;
		try { body = json.readValue(raw); }
		catch (java.io.IOException exception) { throw invalid(); }
		if (body == null || !body.isObject() || body.size() != FIELDS.size() || !FIELDS.stream().allMatch(body::has)
				|| !body.path("ownerAccountId").isTextual() || !body.path("asOf").isTextual()) {
			throw invalid();
		}
		UUID owner;
		Instant asOf;
		try {
			owner = UUID.fromString(body.path("ownerAccountId").textValue());
			if (!owner.toString().equalsIgnoreCase(body.path("ownerAccountId").textValue())) throw invalid();
			asOf = OffsetDateTime.parse(body.path("asOf").textValue()).toInstant();
		}
		catch (IllegalArgumentException | java.time.format.DateTimeParseException exception) {
			throw invalid();
		}
		return new Eligibility(eligibility.eligible(owner, asOf));
	}

	private static ApiException invalid() {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
	}

	record Eligibility(boolean eligible) { }
}

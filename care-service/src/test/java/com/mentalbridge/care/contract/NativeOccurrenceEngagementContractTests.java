package com.mentalbridge.care.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class NativeOccurrenceEngagementContractTests {

	@Test
	void nativeEngagementEventRetainsOwnerEvidenceAndExcludesPrivateReflection() throws Exception {
		var path = Path.of("..", "contracts", "events", "care",
				"support-plan-action-occurrence-changed-v2.schema.json");
		var schema = new ObjectMapper().readTree(Files.readString(path));
		var payload = schema.at("/properties/payload");
		var engagement = schema.at("/$defs/boundedEngagement");

		assertThat(payload.at("/properties/changeType/enum")).extracting(node -> node.asText())
				.contains("CREATED", "STATE_CHANGED", "EVIDENCE_ATTACHED", "CANCELLED", "ENGAGEMENT_CHANGED");
		assertThat(payload.at("/properties/engagement/$ref").asText()).isEqualTo("#/$defs/boundedEngagement");
		assertThat(payload.path("required")).extracting(node -> node.asText()).doesNotContain("engagement");
		assertThat(engagement.path("additionalProperties").asBoolean()).isFalse();
		assertThat(engagement.path("properties").fieldNames()).toIterable()
				.containsExactlyInAnyOrder("hidden", "helpfulness", "barrierCode", "summaryReuseApproved", "updatedAt")
				.doesNotContain("reflection", "journalText", "emotionValue", "assessmentAnswers");
		assertThat(engagement.path("required")).extracting(node -> node.asText())
				.containsExactlyInAnyOrder("hidden", "helpfulness", "barrierCode", "summaryReuseApproved", "updatedAt");
		com.fasterxml.jackson.databind.JsonNode rule = null;
		for (var candidate : payload.path("allOf")) {
			if ("ENGAGEMENT_CHANGED".equals(candidate.at("/if/properties/changeType/const").asText())) rule = candidate;
		}
		assertThat(rule).isNotNull();
		assertThat(rule.at("/if/properties/changeType/const").asText()).isEqualTo("ENGAGEMENT_CHANGED");
		assertThat(rule.at("/then/required")).extracting(node -> node.asText()).contains("engagement");
		assertThat(rule.at("/then/properties/representationMode/const").asText()).isEqualTo("NATIVE_V3");
		assertThat(payload.at("/properties/ownerFact").isMissingNode()).isFalse();
	}
}

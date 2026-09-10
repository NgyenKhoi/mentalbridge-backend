package com.mentalbridge.care.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class CareEventContractTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void assessmentSubmittedSchemaIsStrictAndExcludesRawAnswers() throws Exception {
		var path = Path.of("..", "contracts", "events", "care", "assessment-submitted-v2.schema.json")
				.toAbsolutePath();
		var schema = objectMapper.readTree(Files.readString(path));
		var payload = schema.at("/properties/payload");

		assertThat(schema.get("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
		assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
		assertThat(payload.get("additionalProperties").asBoolean()).isFalse();
		assertThat(payload.get("properties").fieldNames()).toIterable()
				.doesNotContain("answers", "answerValues", "questionAnswers", "sessionToken");
		assertThat(schema.at("/properties/schemaVersion/const").asText()).isEqualTo("2.0");
		assertThat(payload.get("required")).extracting(node -> node.asText()).contains(
				"definitionId", "instrument", "totalScore", "safetyStatus", "safetyPolicyVersion", "completedAt");
		assertThat(payload.at("/properties/instrument/enum")).extracting(node -> node.asText())
				.containsExactly("PHQ9", "GAD7");
		assertThat(payload.at("/properties/safetyStatus/enum")).extracting(node -> node.asText())
				.contains("NOT_APPLICABLE");
	}

	@Test
	void assessmentSubmittedV1RemainsPhq9Only() throws Exception {
		var path = Path.of("..", "contracts", "events", "care", "assessment-submitted-v1.schema.json")
				.toAbsolutePath();
		var schema = objectMapper.readTree(Files.readString(path));

		assertThat(schema.at("/properties/schemaVersion/const").asText()).isEqualTo("1.0");
		assertThat(schema.at("/properties/payload/properties/instrument/const").asText()).isEqualTo("PHQ9");
		assertThat(schema.at("/properties/payload/properties/safetyStatus/enum"))
				.extracting(node -> node.asText())
				.containsExactly("NEGATIVE_SAFETY_SCREEN", "POSITIVE_SAFETY_SCREEN");
	}
}

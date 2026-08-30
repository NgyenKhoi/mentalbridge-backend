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
		var path = Path.of("..", "contracts", "events", "care", "assessment-submitted-v1.schema.json")
				.toAbsolutePath();
		var schema = objectMapper.readTree(Files.readString(path));
		var payload = schema.at("/properties/payload");

		assertThat(schema.get("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
		assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
		assertThat(payload.get("additionalProperties").asBoolean()).isFalse();
		assertThat(payload.get("properties").fieldNames()).toIterable()
				.doesNotContain("answers", "answerValues", "questionAnswers", "sessionToken");
		assertThat(payload.get("required")).anySatisfy(node -> assertThat(node.asText()).isEqualTo("safetyPolicyVersion"));
	}
}
